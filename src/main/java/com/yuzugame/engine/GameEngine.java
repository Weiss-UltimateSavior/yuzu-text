package com.yuzugame.engine;

import com.yuzugame.agent.*;
import com.yuzugame.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.service.InputAuditor;
import com.yuzugame.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.yuzugame.engine.GameStateManager.AgentType;

/**
 * 游戏引擎核心 —— 负责整个游戏主循环的调度与状态流转。
 *
 * <p>架构定位：MVC 中的 Controller 层之下、Agent 层之上的编排层。
 * 每次玩家输入都会经过 {@link #processMessage}，由本类按顺序调度
 * 各 AI Agent（柚子、地图、NPC、谜题、导演）生成输出，
 * 并通过 {@link GameStateManager} 解析控制标签、修改游戏状态。</p>
 *
 * <p>核心流程（每回合）：
 * <ol>
 *   <li>回合计数 + 理智自然衰减</li>
 *   <li>地图切换后即时生成过渡环境描写</li>
 *   <li>柚子（主角AI）对玩家输入的回应（NPC对话时跳过）</li>
 *   <li>探索关键词匹配 → 地图AI环境描写 + 自动激活谜题
 *       （出口已开+移动关键词时跳过；谜题活跃时跳过地图AI描述）</li>
 *   <li>NPC交互（@NPC名 消息格式）→ 自动解锁 + 对话</li>
 *   <li>活跃谜题处理（所有输入都经过谜题AI）</li>
 *   <li>每10回合导演阶段汇报</li>
 *   <li>出口解锁与地图切换（谜题解决→出口开→玩家移动→过渡描写）</li>
 *   <li>理智警告（60/30/10阈值，每个只触发一次）</li>
 *   <li>结局判定</li>
 * </ol></p>
 */
@Component
public class GameEngine {

    private static final Logger log = LoggerFactory.getLogger(GameEngine.class);

    private final DirectorAI director;
    private final ProtagonistAI protagonist;
    private final MapAI mapAI;
    private final NpcAI npcAI;
    private final PuzzleAI puzzleAI;
    private final GameStateManager stateManager;
    private final ConditionEvaluator evaluator;
    private final GameDataLoader dataLoader;
    private final InputAuditor inputAuditor;
    private final ObjectMapper objectMapper;

    /** 模式缓存使用 volatile 保证多线程可见性，避免读到部分构造对象 */
    private volatile Pattern exploreKeywordsPattern;
    private volatile Pattern moveKeywordsPattern;
    private volatile Pattern npcMentionPattern;
    /** 缓存版本，与 GameDataLoader 配置版本对应，配置重载时失效 */
    private volatile int patternCacheVersion = -1;

    public GameEngine(DirectorAI director, ProtagonistAI protagonist, MapAI mapAI,
                      NpcAI npcAI, PuzzleAI puzzleAI, GameStateManager stateManager,
                      ConditionEvaluator evaluator, GameDataLoader dataLoader,
                      InputAuditor inputAuditor, ObjectMapper objectMapper) {
        this.director = director;
        this.protagonist = protagonist;
        this.mapAI = mapAI;
        this.npcAI = npcAI;
        this.puzzleAI = puzzleAI;
        this.stateManager = stateManager;
        this.evaluator = evaluator;
        this.dataLoader = dataLoader;
        this.inputAuditor = inputAuditor;
        this.objectMapper = objectMapper;
        this.dataLoader.setGameEngine(this);
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    /** 检查配置版本，若配置已重载则清空模式缓存 */
    private void refreshPatternCacheIfNeeded() {
        int currentVersion = dataLoader.getConfigVersion();
        if (patternCacheVersion != currentVersion) {
            synchronized (this) {
                if (patternCacheVersion != currentVersion) {
                    exploreKeywordsPattern = null;
                    moveKeywordsPattern = null;
                    npcMentionPattern = null;
                    patternCacheVersion = currentVersion;
                }
            }
        }
    }

    private Pattern exploreKeywords() {
        refreshPatternCacheIfNeeded();
        Pattern p = exploreKeywordsPattern;
        if (p == null) {
            synchronized (this) {
                p = exploreKeywordsPattern;
                if (p == null) {
                    String kw = gameConfig().getExploreKeywords();
                    p = Pattern.compile(kw != null ? kw : "探索|查看|观察|检查|搜索");
                    exploreKeywordsPattern = p;
                }
            }
        }
        return p;
    }

    private Pattern moveKeywords() {
        refreshPatternCacheIfNeeded();
        Pattern p = moveKeywordsPattern;
        if (p == null) {
            synchronized (this) {
                p = moveKeywordsPattern;
                if (p == null) {
                    String kw = gameConfig().getMoveKeywords();
                    p = Pattern.compile(kw != null ? kw : "离开|前进|走|移动|前往");
                    moveKeywordsPattern = p;
                }
            }
        }
        return p;
    }

    private Pattern npcMention() {
        refreshPatternCacheIfNeeded();
        Pattern p = npcMentionPattern;
        if (p == null) {
            synchronized (this) {
                p = npcMentionPattern;
                if (p == null) {
                    String kw = gameConfig().getNpcMentionPattern();
                    p = Pattern.compile(kw != null ? kw : "@(.+?)\\s+(.+)");
                    npcMentionPattern = p;
                }
            }
        }
        return p;
    }

    /**
     * 显式清空模式缓存。
     *
     * <p>通常无需手动调用 —— {@link #refreshPatternCacheIfNeeded()} 会通过配置版本号
     * 自动检测配置重载并失效缓存。此方法保留供外部显式调用。</p>
     */
    public void clearPatternCache() {
        synchronized (this) {
            exploreKeywordsPattern = null;
            moveKeywordsPattern = null;
            npcMentionPattern = null;
            patternCacheVersion = -1;
        }
    }

    private String outputPrefix(String key) {
        Map<String, String> prefixes = gameConfig().getOutputPrefixes();
        return prefixes != null ? prefixes.getOrDefault(key, "【" + key + "】") : "【" + key + "】";
    }

    /**
     * 处理玩家每回合输入 —— 游戏主循环入口。
     *
     * <p>按固定顺序调度各 AI Agent，收集输出，最后判定结局。
     * 所有 AI 输出中的控制标签会被 GameStateManager 解析并执行。</p>
     *
     * @param session 当前游戏会话
     * @param playerMessage 玩家输入的文本
     * @return 拼接后的所有输出段落（含【柚子】【环境】【谜题】等前缀）
     */
    public String processMessage(GameSession session, String playerMessage) {
        if (session.isEnded()) {
            return "[游戏已结束 - " + session.getEndingType() + "]";
        }

        String auditResult = inputAuditor.audit(playerMessage);
        if (auditResult != null) {
            log.warn("Player input blocked by auditor for session {}", session.getSessionId());
            return auditResult;
        }

        String snapshot = snapshotSession(session);

        try {
            return doProcessMessage(session, playerMessage);
        } catch (LlmService.LlmCallException e) {
            log.error("LLM call failed during turn for session {}, rolling back: {}", session.getSessionId(), e.getMessage());
            restoreSession(session, snapshot);
            return "【系统】AI 服务暂时不可用，请稍后重试。（" + e.getMessage() + "）";
        }
    }

    private String doProcessMessage(GameSession session, String playerMessage) {
        // C4 注意：以下配置在方法入口处一次性读取，确保同一回合内配置一致。
        // 若管理员在回合执行期间触发 /data/reload，后续回合才会使用新配置。
        StoryConfig story = dataLoader.getStory();
        MapConfig currentMap = dataLoader.getMap(session.getCurrentMapId());
        if (currentMap == null) {
            log.error("Invalid mapId: {}, attempting recovery", session.getCurrentMapId());
            List<StoryConfig.ChapterDef> chapters = story.getChapters();
            if (chapters != null) {
                for (StoryConfig.ChapterDef ch : chapters) {
                    if (ch.getId() != null && ch.getId().equals(session.getCurrentChapter())) {
                        session.setCurrentMapId(ch.getMapId());
                        break;
                    }
                }
            }
            currentMap = dataLoader.getMap(session.getCurrentMapId());
            if (currentMap == null) {
                session.setCurrentMapId(gameConfig().getStartingMapId());
                session.setCurrentChapter(gameConfig().getStartingChapter());
                currentMap = dataLoader.getMap(gameConfig().getStartingMapId());
            }
            log.info("Recovered map to: {}", session.getCurrentMapId());
        }
        ProtagonistConfig protagonistConfig = dataLoader.getProtagonist();
        List<String> outputs = new ArrayList<>();

        Set<String> npcsUnlockedBefore = new HashSet<>(session.getUnlockedNpcs());

        // ---- 步骤1：回合计数与理智自然衰减 ----
        // 每回合开始时递增回合数，并根据衰减曲线扣除理智值
        session.incrementTurn();
        applySanityDecay(session, story);

        // ---- 步骤2：柚子（主角AI）回应（与NPC对话时跳过） ----
        // NPC对话时跳过柚子回应，避免同一轮出现两个角色抢话
        boolean isNpcDialogue = npcMention().matcher(playerMessage).find();
        session.addChatMessage(new GameSession.ChatMessage("PLAYER", null, playerMessage));
        if (!isNpcDialogue) {
            String yuzuResponse = protagonist.respond(session, protagonistConfig, currentMap, playerMessage);
            String stripped = stateManager.stripInternal(yuzuResponse);
            if (!stripped.isBlank()) {
                outputs.add(outputPrefix("protagonist") + stripped);
            }
            session.addChatMessage(new GameSession.ChatMessage("PROTAGONIST_AI", null, stripped));
            stateManager.applyControlTags(session, yuzuResponse, AgentType.PROTAGONIST);
        }

        // ---- 步骤3：探索关键词匹配 → 地图AI环境描写 ----
        // 跳过条件：谜题已激活 → 地图AI描述与谜题AI描述冗余，由谜题AI统一处理环境叙事
        // 注意：出口已解锁时仍允许探索，MapAI 会根据上下文判断玩家是否要离开并输出 MAP:mapId 标签
        boolean hasActivePuzzle = session.getActivePuzzleId() != null;

        if (exploreKeywords().matcher(playerMessage).find()) {
            if (!hasActivePuzzle) {
                String activePuzzleBefore = session.getActivePuzzleId();
                String mapDesc = mapAI.describe(session, currentMap);
                String mapStripped = stateManager.stripInternal(mapDesc);
                if (!mapStripped.isBlank()) {
                    outputs.add(outputPrefix("environment") + mapStripped);
                    session.addChatMessage(new GameSession.ChatMessage("MAP_AI", null, mapStripped));
                }
                stateManager.applyControlTags(session, mapDesc, AgentType.MAP);

                String activePuzzleAfter = session.getActivePuzzleId();
                if (activePuzzleAfter != null && !activePuzzleAfter.equals(activePuzzleBefore)) {
                    PuzzleConfig activatedPuzzle = dataLoader.getPuzzle(activePuzzleAfter);
                    if (activatedPuzzle != null) {
                        outputs.add(outputPrefix("system") + "谜题已激活：" + activatedPuzzle.getName() + " — " + activatedPuzzle.getDescription());
                    }
                }

                // 谜题兜底激活：在同一地图停留>=5回合且无活跃谜题时，自动激活第一个未解决谜题
                // 阈值调整：修改 getMapTurns() >= 5 中的数字
                if (session.getActivePuzzleId() == null && session.getMapTurns() >= gameConfig().getFallbackPuzzleActivationTurns()) {
                    List<String> mapPuzzles = currentMap.getPuzzles();
                    if (mapPuzzles != null) {
                        for (String pId : mapPuzzles) {
                            if (!session.isPuzzleSolved(pId) && !session.getFailedPuzzles().contains(pId)) {
                                session.setActivePuzzleId(pId);
                                PuzzleConfig puzzle = dataLoader.getPuzzle(pId);
                                if (puzzle != null) {
                                    outputs.add(outputPrefix("system") + "谜题已激活：" + puzzle.getName() + " — " + puzzle.getDescription());
                                }
                                log.debug("Fallback puzzle activation at turn {}: {}", session.getTurn(), pId);
                                break;
                            }
                        }
                    }
                }
            }

            autoPickupItems(session, currentMap, outputs);

            // 档案馆10层特殊奖励：每次探索额外+3揭露度（档案馆是信息密集区域）
            if ("map_archive_10f".equals(session.getCurrentMapId())) {
                int revGain = gameConfig().getArchiveRevelationBonus();
                session.getPlayer().addRevelation(revGain);
                log.debug("Archive exploration revelation: +{} -> now {}", revGain, session.getPlayer().getRevelation());
            }

            session.getPlayer().addRevelation(gameConfig().getExplorationRevelationBonus());
            log.debug("Exploration revelation: +1 -> now {}", session.getPlayer().getRevelation());
        }

        // ---- 步骤4：NPC交互（@NPC名 消息格式） ----
        // 解析 @NPC名 消息格式，自动解锁满足条件的NPC，执行对话
        // NPC交互与柚子回应互斥：Step3 已跳过柚子
        Matcher npcMatcher = npcMention().matcher(playerMessage);
        if (npcMatcher.find()) {
            String npcName = npcMatcher.group(1);
            String npcMsg = npcMatcher.group(2);
            NpcConfig npc = findNpcByName(npcName);

            if (npc == null) {
                String tpl = gameConfig().getOutputPrefixes().getOrDefault("npcNotFound", "【提示】迷雾尚未揭露。");
                outputs.add(tpl);
            } else {
                // 检查NPC是否属于当前地图
                List<String> mapNpcIds = currentMap.getNpcIds();
                boolean npcInCurrentMap = mapNpcIds != null && mapNpcIds.contains(npc.getId());

                if (!npcInCurrentMap) {
                    String tpl = gameConfig().getOutputPrefixes().getOrDefault("npcNotHere", "【提示】{npcName}不在这里，你需要在更深层的地方寻找。");
                    outputs.add(tpl.replace("{npcName}", npc.getName()));
                } else {
                    if (!session.isNpcUnlocked(npc.getId())) {
                        if (evaluator.evaluateOr(session, npc.getAppearCondition())) {
                            stateManager.applyControlTags(session, "NPC:UNLOCK:" + npc.getId(), AgentType.MAP);
                            log.debug("Auto-unlocked NPC {} (appearCondition met)", npc.getId());
                        } else {
                            outputs.add(outputPrefix("hint") + npc.getDialogueHint());
                        }
                    }
                    if (session.isNpcUnlocked(npc.getId())) {
                        if (npc.getDialogueCondition() != null && !npc.getDialogueCondition().isBlank()
                                && !evaluator.evaluateOr(session, npc.getDialogueCondition())) {
                            String tpl = gameConfig().getOutputPrefixes().getOrDefault("npcRefuseTalk", "【提示】{npcName}此刻不愿与你交谈。");
                            outputs.add(tpl.replace("{npcName}", npc.getName()));
                        } else {
                            String npcResp = npcAI.respond(session, npc, currentMap, npcMsg);
                            String npcStripped = stateManager.stripInternal(npcResp);
                            if (!npcStripped.isBlank()) {
                                outputs.add("【" + npc.getName() + "】" + npcStripped);
                            }
                            session.addChatMessage(new GameSession.ChatMessage("NPC_AI", npc.getId(), npcStripped));
                            stateManager.applyControlTags(session, npcResp, AgentType.NPC);
                            int prevCount = session.getNpcDialogueCount(npc.getId());
                            session.incrementNpcDialogueCount(npc.getId());

                            // N1 修复：礼物自动发放改为 >= threshold-1 且玩家和柚子均未持有该礼物
                            String giftIdTemplate = gameConfig().getNpcGiftItemIdTemplate();
                            if (giftIdTemplate != null && prevCount >= gameConfig().getNpcGiftDialogueThreshold() - 1) {
                                String giftId = giftIdTemplate.replace("{npcId}", npc.getId());
                                if (!session.getPlayer().hasItem(giftId) && !session.yuzuHasItem(giftId)) {
                                    String giftNameTemplate = gameConfig().getNpcGiftNameTemplate();
                                    if (giftNameTemplate != null) {
                                        String giftName = giftNameTemplate.replace("{npcName}", npc.getName());
                                        session.getPlayer().addItem(giftId);
                                        session.registerDynamicItemName(giftId, giftName);
                                        outputs.add(outputPrefix("system") + "获得了「" + giftName + "」");
                                        log.info("[NPC:{}] Auto-gave item {} ({}) on dialogue count {} (threshold {})", npc.getId(), giftId, giftName, prevCount + 1, gameConfig().getNpcGiftDialogueThreshold());
                                    }
                                }
                            }

                            session.getPlayer().addRevelation(gameConfig().getNpcDialogueRevelationBonus());
                            log.debug("NPC dialogue revelation: +1 -> now {}", session.getPlayer().getRevelation());
                        }
                    }
                }
            }
        }

        // ---- 步骤5：活跃谜题处理 ----
        // 当有激活的谜题时，所有玩家输入都经过谜题AI处理
        // 谜题AI负责判定交互是否有效、推进解谜进度、输出 PUZZLE:SOLVE/FAIL 标签
        if (session.getActivePuzzleId() != null) {
            PuzzleConfig puzzle = dataLoader.getPuzzle(session.getActivePuzzleId());
            if (puzzle != null) {
                String puzzleResp = puzzleAI.handle(session, puzzle, playerMessage);
                String puzzleStripped = stateManager.stripInternal(puzzleResp);
                if (!puzzleStripped.isBlank()) {
                    outputs.add(outputPrefix("puzzle") + puzzleStripped);
                    session.addChatMessage(new GameSession.ChatMessage("PUZZLE_AI", null, puzzleStripped));
                }
                stateManager.applyControlTags(session, puzzleResp, AgentType.PUZZLE);
                recordPuzzleMemory(session, puzzle.getId(), playerMessage, puzzleStripped, gameConfig().getMaxPuzzleMemoryRounds());

                // 谜题解决后：清理谜题记忆 + 基于难度的揭露度/理智奖励
                // 奖励表（按难度1~5递增）：
                //   难度 | 揭露度 | 理智
                //     1  |   +5   |  +2
                //     2  |   +7   |  +2
                //     3  |   +9   |  +2
                //     4  |  +11   |  +2
                //    5+  |  +11   |  +2
                // 调整方式：修改下方 switch 表达式中的数值
                if (session.isPuzzleSolved(puzzle.getId())) {
                    session.clearPuzzleMemory(puzzle.getId());
                    GameConfig.PuzzleRewards rewards = gameConfig().getPuzzleRewards();
                    if (rewards != null) {
                        int revelationReward = rewards.getRevelationReward(puzzle.getDifficulty());
                        session.getPlayer().addRevelation(revelationReward);
                        int sanityReward = rewards.getSanityReward(puzzle.getDifficulty());
                        session.getPlayer().addSanity(sanityReward);
                        log.debug("Puzzle solved rewards: revelation +{} -> now {}, sanity +{} -> now {}",
                                revelationReward, session.getPlayer().getRevelation(),
                                sanityReward, session.getPlayer().getSanity());
                    }
                }
            }
        }

        // ---- 步骤7前：捕获地图切换前的状态基线 ----
        // 必须在步骤7（DirectorAI）之前捕获，因为 DirectorAI 的控制标签
        // 可能修改 session 状态，导致过渡描写基于错误的起始地图
        String mapIdBeforeTransition = session.getCurrentMapId();
        String chapterBeforeTransition = session.getCurrentChapter();

        // ---- 步骤7：每10回合导演阶段汇报 ----
        // 导演AI生成阶段性旁白，提供叙事节奏感，同时可输出控制标签
        if (session.getTurn() % gameConfig().getStageReportInterval() == 0) {
            String report = director.stageReport(session, currentMap, story);
            String reportStripped = stateManager.stripInternal(report);
            if (!reportStripped.isBlank()) {
                outputs.add(outputPrefix("director") + reportStripped);
                session.addChatMessage(new GameSession.ChatMessage("DIRECTOR_AI", null, reportStripped));
            }
            stateManager.applyControlTags(session, report, AgentType.DIRECTOR);
        }

        // ---- 步骤7.5：NPC解锁通知 ----
        // 统一检测本回合新增的解锁NPC，补充出场描写通知
        // 适用于步骤5自动解锁和 NPC:UNLOCK 标签两条路径
        Set<String> npcsUnlockedAfter = session.getUnlockedNpcs();
        for (String npcId : npcsUnlockedAfter) {
            if (!npcsUnlockedBefore.contains(npcId)) {
                NpcConfig npcCfg = dataLoader.getNpc(npcId);
                if (npcCfg != null) {
                    outputs.add(outputPrefix("system") + npcCfg.getName() + "出现了 — " + npcCfg.getDescription());
                    outputs.add(outputPrefix("system") + "理智 +" + gameConfig().getNpcUnlockSanityReward());
                }
            }
        }

        // ---- 步骤8：出口解锁与地图切换 ----
        // 流程：谜题解决/失败 → 出口解锁 → 玩家输入"离开"/"前进" → 地图切换 + 过渡描写
        // 地图切换统一通过 GameStateManager.handleMap() 执行状态重置，
        // 过渡描写、章节奖励、物品拾取在末尾统一处理

        if (!session.isExitUnlocked() && shouldCheckMapTransition(session, currentMap)) {
            session.setExitUnlocked(true);
                String exitHint = currentMap.getExitHint();
                outputs.add(outputPrefix("system") + "出口已开启" + (exitHint != null ? " — " + exitHint : ""));
        }

        // 地图切换：仅通过精确匹配"离开"或"前进"触发
        // 前端有提示按钮，玩家只会输入精确的"离开"或"前进"
        if (session.isExitUnlocked() && ("离开".equals(playerMessage) || "前进".equals(playerMessage))
                && currentMap.getNextMapId() != null && !currentMap.getNextMapId().isBlank()) {
            stateManager.handleMap(session, currentMap.getNextMapId());
        }

        // 地图切换后统一处理：过渡描写 + 章节奖励 + 物品拾取
        // 无论是 Step8 出口移动路径还是 MAP:mapId 标签路径，都在此统一执行
        if (!session.getCurrentMapId().equals(mapIdBeforeTransition)) {
            currentMap = dataLoader.getMap(session.getCurrentMapId());
            outputs.removeIf(o -> o.startsWith(outputPrefix("environment")));
            MapConfig fromMap = dataLoader.getMap(mapIdBeforeTransition);
            if (currentMap != null) {
                if (!Objects.equals(currentMap.getChapter(), chapterBeforeTransition)) {
                    int chapterReward = gameConfig().getChapterRevelationReward(currentMap.getChapter());
                    session.getPlayer().addRevelation(chapterReward);
                    log.debug("Chapter advancement revelation: +{} -> now {}", chapterReward, session.getPlayer().getRevelation());
                }

                String transitionDesc = (fromMap != null)
                        ? mapAI.transitionDescribe(session, fromMap, currentMap)
                        : mapAI.autoDescribe(session, currentMap);
                String transitionStripped = stateManager.stripInternal(transitionDesc);
                if (!transitionStripped.isBlank()) {
                    outputs.add(outputPrefix("environment") + transitionStripped);
                    session.addChatMessage(new GameSession.ChatMessage("MAP_AI", null, transitionStripped));
                }
                stateManager.applyControlTags(session, transitionDesc, AgentType.MAP);
                autoPickupItems(session, currentMap, outputs);
            }
        }

        // ---- 步骤9：理智阈值警告（每个阈值只触发一次） ----
        // 当理智降至阈值以下时，导演AI生成内心独白，增强叙事紧迫感
        // 阈值调整：修改下方 thresholds 数组，如 {60, 30, 10} 表示理智≤60/≤30/≤10时各触发一次
        int sanity = session.getPlayer().getSanity();
        boolean sanityWarningTriggered = false;
        List<Integer> thresholds = gameConfig().getSanityWarningThresholds();
        if (thresholds != null) {
            for (int threshold : thresholds) {
                sanity = session.getPlayer().getSanity();
                if (sanity <= threshold && !session.getTriggeredSanityWarnings().contains(threshold)) {
                    session.triggerSanityWarning(threshold);
                    sanityWarningTriggered = true;
                    String warning = director.sanityWarning(session, currentMap, story);
                    String warnStripped = stateManager.stripInternal(warning);
                    if (!warnStripped.isBlank()) {
                        outputs.add(outputPrefix("innerVoice") + warnStripped);
                        session.addChatMessage(new GameSession.ChatMessage("DIRECTOR_AI", null, warnStripped));
                    }
                    stateManager.applyControlTags(session, warning, AgentType.DIRECTOR);
                }
            }
        }

        sanity = session.getPlayer().getSanity();
        if (sanityWarningTriggered && session.yuzuHasItem("item_sedative") && sanity < 20 && session.getTurn() < 60) {
            session.removeYuzuItem("item_sedative");
            ItemConfig sedative = dataLoader.getItem("item_sedative");
            int recovery = (sedative != null && sedative.getSanityRecovery() != null) ? sedative.getSanityRecovery() : 20;
            session.getPlayer().addSanity(recovery);
            sanity = session.getPlayer().getSanity();
            String sedativeMsg = "（柚子注意到你的状态不太对……她果断从口袋里掏出镇定剂，稳稳地给你注射了进去）老师，深呼吸……会好起来的。";
            outputs.add(outputPrefix("protagonist") + sedativeMsg);
            outputs.add(outputPrefix("system") + "柚子为你使用了镇定剂，理智恢复+" + recovery);
            session.addChatMessage(new GameSession.ChatMessage("PROTAGONIST_AI", null, sedativeMsg));
            log.debug("Sedative auto-used: sanity +{} -> now {}", recovery, sanity);
        }

        // ---- 步骤10：结局判定 ----
        // 导演AI根据理智值和揭露度判定是否触发结局，若触发则生成结局内容并终止游戏
        String endingType = director.determineEndingAction(session, story);
        if (endingType != null) {
            handleEnding(session, endingType, story, protagonistConfig, outputs);
        }

        return String.join("\n\n", outputs);
    }

    static void recordPuzzleMemory(GameSession session, String puzzleId, String playerMessage,
                                   String puzzleResponse, int maxRounds) {
        if (session == null || puzzleId == null || puzzleId.isBlank()) {
            return;
        }
        if (playerMessage != null && !playerMessage.isBlank()) {
            session.addPuzzleMemoryEntry(puzzleId, new PuzzleMemoryEntry("user", playerMessage));
        }
        if (puzzleResponse != null && !puzzleResponse.isBlank()) {
            session.addPuzzleMemoryEntry(puzzleId, new PuzzleMemoryEntry("assistant", puzzleResponse));
        }
        if (maxRounds > 0) {
            session.truncatePuzzleMemory(puzzleId, maxRounds);
        }
    }

    /**
     * 初始化新游戏 —— 生成导演开场白。
     *
     * <p>过滤掉 MAP/CHAPTER/ENDING 标签，防止开场阶段被 LLM 篡改地图/章节。
     * 只保留 SANITY/REVELATION/AFFECTION 等属性标签。</p>
     */
    public String initGame(GameSession session) {
        StoryConfig story = dataLoader.getStory();
        session.setCurrentMapId(gameConfig().getStartingMapId());
        session.setCurrentChapter(gameConfig().getStartingChapter());
        session.setCurrentArea(null);
        session.setGamePhase("opening");
        session.setTurn(0);
        session.addYuzuItem("item_sedative");

        MapConfig startMap = dataLoader.getMap(gameConfig().getStartingMapId());
        try {
            String opening = director.openingMonologue(session, story, startMap);
            String stripped = stateManager.stripInternal(opening);
            List<String> tags = stateManager.extractTags(opening);
            List<String> safeTags = tags.stream()
                    .filter(t -> !t.startsWith("MAP:") && !t.startsWith("CHAPTER:") && !t.startsWith("ENDING:"))
                    .toList();
            stateManager.executeTags(session, safeTags, AgentType.DIRECTOR);
            return outputPrefix("directorOpening") + "\n" + stripped;
        } catch (LlmService.LlmCallException e) {
            log.error("LLM call failed during initGame for session {}: {}", session.getSessionId(), e.getMessage());
            return "【系统】AI 服务暂时不可用，请稍后重试。（" + e.getMessage() + "）";
        }
    }

    /**
     * 处理开场阶段 —— 玩家首次输入后触发柚子登场。
     *
     * <p>开场阶段（gamePhase=opening）只执行一次，之后切换到 playing 阶段。</p>
     */
    public String processOpening(GameSession session) {
        StoryConfig story = dataLoader.getStory();
        String snapshot = snapshotSession(session);

        try {
            session.incrementTurn();
            applySanityDecay(session, story);

            ProtagonistConfig config = dataLoader.getProtagonist();
            MapConfig currentMap = dataLoader.getMap(session.getCurrentMapId());
            if (currentMap == null) {
                log.error("Invalid currentMapId: {}, resetting to {}", session.getCurrentMapId(), gameConfig().getStartingMapId());
                session.setCurrentMapId(gameConfig().getStartingMapId());
                currentMap = dataLoader.getMap(gameConfig().getStartingMapId());
            }

            String yuzuIntro = protagonist.opening(session, config, currentMap);
            String stripped = stateManager.stripInternal(yuzuIntro);
            session.addChatMessage(new GameSession.ChatMessage("PROTAGONIST_AI", null, stripped));
            stateManager.applyControlTags(session, yuzuIntro, AgentType.PROTAGONIST);

            session.setGamePhase("playing");

            return outputPrefix("protagonist") + stripped;
        } catch (LlmService.LlmCallException e) {
            log.error("LLM call failed during processOpening for session {}: {}", session.getSessionId(), e.getMessage());
            restoreSession(session, snapshot);
            return "【系统】AI 服务暂时不可用，请稍后重试。（" + e.getMessage() + "）";
        }
    }

    /**
     * 判断是否应检查地图切换。
     *
     * <p>条件：当前地图有下一地图 && 当前地图的谜题已解决。</p>
     * <p>谜题不再有永久失败机制，玩家可以一直尝试直到成功。</p>
     */
    private boolean shouldCheckMapTransition(GameSession session, MapConfig currentMap) {
        if (currentMap.getNextMapId() == null || currentMap.getNextMapId().isBlank()) {
            return false;
        }
        String unlockCondition = currentMap.getUnlockCondition();
        if (unlockCondition != null && !unlockCondition.isBlank()) {
            return evaluator.evaluateOr(session, unlockCondition);
        }
        boolean anyPuzzleSolved = currentMap.getPuzzles() != null &&
                currentMap.getPuzzles().stream().anyMatch(session::isPuzzleSolved);
        return anyPuzzleSolved;
    }

    /**
     * 根据理智衰减曲线（story.json 中定义）对玩家理智进行自然衰减。
     *
     * <p>衰减速率随回合数递增，由 {@code story.json → sanityDecayCurve} 配置：
     * <ul>
     *   <li>回合 1~29：每回合 -1</li>
     *   <li>回合 30~59：每回合 -2</li>
     *   <li>回合 60~99：每回合 -3</li>
     * </ul></p>
     *
     * <p>调整方式：修改 {@code src/main/resources/data/story.json} 中的 {@code sanityDecayCurve} 数组。</p>
     *
     * @see StoryConfig.SanityDecayCurve
     */
    private void applySanityDecay(GameSession session, StoryConfig story) {
        if (session.getTurn() > 0) {
            int decay = story.getSanityDecayForTurn(session.getTurn());
            session.getPlayer().addSanity(-decay);
        }
    }

    /**
     * 按NPC名称查找NPC配置 —— 用于 @NPC名 交互时的名称匹配。
     * 遍历所有NPC配置，返回名称完全匹配的NPC，未找到则返回null。
     *
     * @param name 玩家输入的NPC名称（@后面的部分）
     * @return 匹配的NPC配置，未找到返回null
     */
    private NpcConfig findNpcByName(String name) {
        if (name == null) return null;
        for (NpcConfig npc : dataLoader.getNpcs()) {
            if (name.equals(npc.getName())) {
                return npc;
            }
        }
        return null;
    }

    /**
     * 自动拾取当前地图的未发现物品 —— 作为 LLM 不输出 ITEM:GIVE/FOUND 标签的兜底。
     *
     * <p>每次探索时，将当前地图中尚未被发现的物品自动加入玩家背包。
     * 每次探索最多拾取 2 个物品，避免一次性获取过多。</p>
     *
     * <p>拾取上限调整：修改下方 {@code picked >= 2} 中的数字。</p>
     */
    private void autoPickupItems(GameSession session, MapConfig currentMap, List<String> outputs) {
        if (currentMap.getItems() == null) return;
        int picked = 0;
        for (String itemId : currentMap.getItems()) {
            if (!session.isItemFound(itemId) && !session.getPlayer().hasItem(itemId)) {
                session.foundItem(itemId);
                session.getPlayer().addItem(itemId);
                // 为不在预设配置中的物品注册兜底名称
                if (dataLoader.getItem(itemId) == null && session.getDynamicItemName(itemId) == null) {
                    session.registerDynamicItemName(itemId, itemId);
                }
                picked++;
                log.debug("Auto-picked up item: {}", itemId);
            }
            if (picked >= gameConfig().getAutoPickupLimit()) break;
        }
        if (picked > 0) {
            outputs.add(outputPrefix("system") + "获得了 " + picked + " 件物品");
        }
    }

    /**
     * 处理结局 —— 生成结局旁白 + 柚子最终台词，标记游戏结束。
     *
     * @param endingType 结局类型：FAIL / FINAL / PERFECT
     */
    private void handleEnding(GameSession session, String endingType, StoryConfig story,
                               ProtagonistConfig pConfig, List<String> outputs) {
        String ending = director.ending(session, endingType, story);
        String endingStripped = stateManager.stripInternal(ending);
        outputs.add(outputPrefix("ending") + endingStripped);

        String yuzuEnding = protagonist.endingLine(session, session.getPlayer(), pConfig, endingType);
        String yuzuEndingStripped = stateManager.stripInternal(yuzuEnding);
        outputs.add(outputPrefix("protagonistEnding") + yuzuEndingStripped);

        stateManager.applyControlTags(session, ending, AgentType.DIRECTOR);
        session.setEndingType(endingType);
        session.setEnded(true);
        session.clearAllPuzzleMemory();
    }

    private String snapshotSession(GameSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            log.error("Failed to snapshot session {}: {}", session.getSessionId(), e.getMessage());
            return null;
        }
    }

    private void restoreSession(GameSession session, String snapshot) {
        if (snapshot == null) {
            log.warn("No snapshot available for session {}, cannot restore", session.getSessionId());
            return;
        }
        try {
            GameSession restored = objectMapper.readValue(snapshot, GameSession.class);
            copySessionState(restored, session);
            log.info("Session {} restored to pre-turn state", session.getSessionId());
        } catch (Exception e) {
            log.error("Failed to restore session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    private void copySessionState(GameSession src, GameSession dst) {
        dst.getPlayer().copyFrom(src.getPlayer());
        dst.setCurrentMapId(src.getCurrentMapId());
        dst.setCurrentChapter(src.getCurrentChapter());
        dst.setTurn(src.getTurn());
        dst.setGamePhase(src.getGamePhase());
        dst.setActivePuzzleId(src.getActivePuzzleId());
        dst.setMapAutoTriggered(src.isMapAutoTriggered());
        dst.setExitUnlocked(src.isExitUnlocked());
        dst.setMapEntryTurn(src.getMapEntryTurn());
        dst.setCurrentArea(src.getCurrentArea());

        dst.setSolvedPuzzles(new HashSet<>(src.getSolvedPuzzles()));
        dst.setFailedPuzzles(new HashSet<>(src.getFailedPuzzles()));
        dst.setUnlockedNpcs(new HashSet<>(src.getUnlockedNpcs()));
        dst.setFoundItems(new HashSet<>(src.getFoundItems()));
        dst.setTriggeredSanityWarnings(new HashSet<>(src.getTriggeredSanityWarnings()));
        dst.setYuzuInventory(new ArrayList<>(src.getYuzuInventory()));

        dst.setPuzzleAttempts(new HashMap<>(src.getPuzzleAttempts()));
        dst.setNpcDialogueCounts(new HashMap<>(src.getNpcDialogueCounts()));
        dst.setDynamicItemNames(new HashMap<>(src.getDynamicItemNames()));
        dst.setUsedRedemptionCodes(new HashMap<>(src.getUsedRedemptionCodes()));
        Map<String, List<PuzzleMemoryEntry>> pmCopy = new HashMap<>();
        for (var entry : src.getPuzzleMemory().entrySet()) {
            pmCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        dst.setPuzzleMemory(pmCopy);
        dst.setChatHistory(new ArrayList<>(src.getChatHistory()));

        dst.setEnded(src.isEnded());
        dst.setEndingType(src.getEndingType());
        dst.setCustomLlmBaseUrl(src.getCustomLlmBaseUrl());
        dst.setCustomLlmApiKey(src.getCustomLlmApiKey());
        dst.setCustomLlmModel(src.getCustomLlmModel());
    }
}
