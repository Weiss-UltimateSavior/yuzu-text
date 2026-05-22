package com.yuzugame.engine;

import com.yuzugame.agent.*;
import com.yuzugame.model.*;
import com.yuzugame.service.InputAuditor;
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
 *   <li>首次进入新地图时自动环境描写（mapAutoTriggered 标志）</li>
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

    private Pattern exploreKeywordsPattern;
    private Pattern moveKeywordsPattern;
    private Pattern npcMentionPattern;

    public GameEngine(DirectorAI director, ProtagonistAI protagonist, MapAI mapAI,
                      NpcAI npcAI, PuzzleAI puzzleAI, GameStateManager stateManager,
                      ConditionEvaluator evaluator, GameDataLoader dataLoader,
                      InputAuditor inputAuditor) {
        this.director = director;
        this.protagonist = protagonist;
        this.mapAI = mapAI;
        this.npcAI = npcAI;
        this.puzzleAI = puzzleAI;
        this.stateManager = stateManager;
        this.evaluator = evaluator;
        this.dataLoader = dataLoader;
        this.inputAuditor = inputAuditor;
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    private Pattern exploreKeywords() {
        if (exploreKeywordsPattern == null) {
            exploreKeywordsPattern = Pattern.compile(gameConfig().getExploreKeywords());
        }
        return exploreKeywordsPattern;
    }

    private Pattern moveKeywords() {
        if (moveKeywordsPattern == null) {
            moveKeywordsPattern = Pattern.compile(gameConfig().getMoveKeywords());
        }
        return moveKeywordsPattern;
    }

    private Pattern npcMention() {
        if (npcMentionPattern == null) {
            npcMentionPattern = Pattern.compile(gameConfig().getNpcMentionPattern());
        }
        return npcMentionPattern;
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

        StoryConfig story = dataLoader.getStory();
        MapConfig currentMap = dataLoader.getMap(session.getCurrentMapId());
        if (currentMap == null) {
            log.error("Invalid mapId: {}, attempting recovery", session.getCurrentMapId());
            for (StoryConfig.ChapterDef ch : story.getChapters()) {
                if (ch.getId().equals(session.getCurrentChapter())) {
                    session.setCurrentMapId(ch.getMapId());
                    break;
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

        // ---- 步骤1：回合计数与理智自然衰减 ----
        // 每回合开始时递增回合数，并根据衰减曲线扣除理智值
        session.incrementTurn();
        applySanityDecay(session, story);

        // ---- 步骤2：首次进入新地图时自动环境描写 ----
        // mapAutoTriggered 由 GameStateManager.handleMap() 在 LLM 输出 MAP: 标签时设置
        // 若玩家本轮输入含探索关键词，则跳过自动描写（Step4 会补上），避免重复
        if (session.isMapAutoTriggered()) {
            session.setMapAutoTriggered(false);
            if (!exploreKeywords().matcher(playerMessage).find()) {
                String mapDesc = mapAI.autoDescribe(session, currentMap);
                String autoStripped = stateManager.stripInternal(mapDesc);
                if (!autoStripped.isBlank()) {
                    outputs.add(outputPrefix("environment") + autoStripped);
                    session.addChatMessage(new GameSession.ChatMessage("MAP_AI", null, autoStripped));
                }
                stateManager.applyControlTags(session, mapDesc, AgentType.MAP);
            }
        }

        // ---- 步骤3：柚子（主角AI）回应（与NPC对话时跳过） ----
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

        // ---- 步骤4：探索关键词匹配 → 地图AI环境描写 ----
        // 跳过条件1：出口已开且玩家输入含移动关键词 → 玩家意图是移动而非探索，Step8会处理
        // 跳过条件2：谜题已激活 → 地图AI描述与谜题AI描述冗余，由谜题AI统一处理环境叙事
        boolean tryingToMove = session.isExitUnlocked() && moveKeywords().matcher(playerMessage).find();
        boolean hasActivePuzzle = session.getActivePuzzleId() != null;

        if (exploreKeywords().matcher(playerMessage).find() && !tryingToMove) {
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
                    for (String pId : currentMap.getPuzzles()) {
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

        // ---- 步骤5：NPC交互（@NPC名 消息格式） ----
        // 解析 @NPC名 消息格式，自动解锁满足条件的NPC，执行对话
        // NPC交互与柚子回应互斥：Step3 已跳过柚子
        Matcher npcMatcher = npcMention().matcher(playerMessage);
        if (npcMatcher.find()) {
            String npcName = npcMatcher.group(1);
            String npcMsg = npcMatcher.group(2);
            NpcConfig npc = findNpcByName(npcName);

            if (npc == null) {
                outputs.add(outputPrefix("hint") + "迷雾尚未揭露。");
            } else {
                // NPC自动解锁：当出现条件满足时自动解锁，并给予理智+15奖励（首次建立联系的鼓励）
                if (!session.isNpcUnlocked(npc.getId())) {
                    if (evaluator.evaluateOr(session, npc.getAppearCondition())) {
                        session.unlockNpc(npc.getId());
                        session.getPlayer().addSanity(gameConfig().getNpcUnlockSanityReward());
                        log.debug("Auto-unlocked NPC {} (appearCondition met), sanity +15 -> now {}", npc.getId(), session.getPlayer().getSanity());
                    } else {
                        outputs.add(outputPrefix("hint") + npc.getDialogueHint());
                    }
                }
                if (session.isNpcUnlocked(npc.getId())) {
                    if (session.isNpcKilled(npc.getId()) && !session.isNpcRevived(npc.getId())) {
                        outputs.add(outputPrefix("hint") + npc.getName() + "已经不在了。");
                    } else if (!evaluator.evaluateOr(session, npc.getAppearCondition())) {
                        outputs.add(outputPrefix("hint") + npc.getName() + "不在这里，你需要在更深层的地方寻找。");
                    } else if (npc.getDialogueCondition() != null && !npc.getDialogueCondition().isBlank()
                            && !evaluator.evaluateOr(session, npc.getDialogueCondition())) {
                        outputs.add(outputPrefix("hint") + npc.getName() + "此刻不愿与你交谈。");
                    } else {
                        String npcResp = npcAI.respond(session, npc, currentMap, npcMsg);
                        String npcStripped = stateManager.stripInternal(npcResp);
                        if (!npcStripped.isBlank()) {
                            outputs.add("【" + npc.getName() + "】" + npcStripped);
                        }
                        session.addChatMessage(new GameSession.ChatMessage("NPC_AI", npc.getId(), npcStripped));
                        int inventorySizeBefore = session.getPlayer().getInventory().size();
                        stateManager.applyControlTags(session, npcResp, AgentType.NPC);
                        int prevCount = session.getNpcDialogueCount(npc.getId());
                        session.incrementNpcDialogueCount(npc.getId());

                        if (prevCount == gameConfig().getNpcGiftDialogueThreshold() - 1
                                && session.getPlayer().getInventory().size() == inventorySizeBefore) {
                            String giftId = gameConfig().getNpcGiftItemIdTemplate().replace("{npcId}", npc.getId());
                            String giftName = gameConfig().getNpcGiftNameTemplate().replace("{npcName}", npc.getName());
                            session.getPlayer().addItem(giftId);
                            session.registerDynamicItemName(giftId, giftName);
                            outputs.add(outputPrefix("system") + "获得了「" + giftName + "」");
                            log.info("[NPC:{}] Auto-gave item {} ({}) on {}th dialogue (AI did not give item)", npc.getId(), giftId, giftName, gameConfig().getNpcGiftDialogueThreshold());
                        }

                        session.getPlayer().addRevelation(gameConfig().getNpcDialogueRevelationBonus());
                        log.debug("NPC dialogue revelation: +1 -> now {}", session.getPlayer().getRevelation());
                    }
                }
            }
        }

        // ---- 步骤6：活跃谜题处理 ----
        // 当有激活的谜题时，所有玩家输入都经过谜题AI处理
        // 谜题AI负责判定交互是否有效、推进解谜进度、输出 PUZZLE:SOLVE/FAIL 标签
        if (session.getActivePuzzleId() != null) {
            PuzzleConfig puzzle = dataLoader.getPuzzle(session.getActivePuzzleId());
            if (puzzle != null) {
                String puzzleResp = puzzleAI.handle(session, puzzle, currentMap, playerMessage);
                String puzzleStripped = stateManager.stripInternal(puzzleResp);
                if (!puzzleStripped.isBlank()) {
                    outputs.add(outputPrefix("puzzle") + puzzleStripped);
                    session.addChatMessage(new GameSession.ChatMessage("PUZZLE_AI", null, puzzleStripped));
                }
                stateManager.applyControlTags(session, puzzleResp, AgentType.PUZZLE);

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
                    puzzleAI.clearPuzzleMemory(session.getSessionId(), puzzle.getId());
                    int revelationReward = gameConfig().getPuzzleRewards().getRevelationReward(puzzle.getDifficulty());
                    session.getPlayer().addRevelation(revelationReward);
                    int sanityReward = gameConfig().getPuzzleRewards().getSanityReward(puzzle.getDifficulty());
                    session.getPlayer().addSanity(sanityReward);
                    log.debug("Puzzle solved rewards: revelation +{} -> now {}, sanity +{} -> now {}",
                            revelationReward, session.getPlayer().getRevelation(),
                            sanityReward, session.getPlayer().getSanity());
                }
            }
        }

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

        // ---- 步骤8：出口解锁与地图切换 ----
        // 流程：谜题解决/失败 → 出口解锁 → 玩家输入移动关键词 → 地图切换 + 过渡描写
        // 地图切换后：重置出口状态、记录地图进入回合（用于 mapTurns 计算）、拾取新地图物品
        String mapIdBeforeTransition = session.getCurrentMapId();

        if (!session.isExitUnlocked() && shouldCheckMapTransition(session, currentMap)) {
            session.setExitUnlocked(true);
            outputs.add(outputPrefix("system") + "出口已开启 — " + currentMap.getExitHint());
        }

        if (session.isExitUnlocked() && moveKeywords().matcher(playerMessage).find()
                && currentMap.getNextMapId() != null && !currentMap.getNextMapId().isBlank()) {
            String nextMapId = currentMap.getNextMapId();
            MapConfig nextMap = dataLoader.getMap(nextMapId);
            if (nextMap != null) {
                session.setCurrentMapId(nextMapId);
                session.setExitUnlocked(false);
                session.setMapAutoTriggered(false);
                session.setMapEntryTurn(session.getTurn());
                session.setCurrentArea(null);

                for (StoryConfig.ChapterDef ch : story.getChapters()) {
                    if (ch.getMapId().equals(nextMapId)) {
                        session.setCurrentChapter(ch.getId());
                        break;
                    }
                }

                // 章节推进揭露度奖励（进入新章节时一次性获得）：
                //   ch2→+3, ch3→+5, ch4→+7, ch5→+10, 其他→+2
                // 调整方式：修改下方 switch 表达式中的数值
                int chapterReward = gameConfig().getChapterRevelationReward(nextMap.getChapter());
                session.getPlayer().addRevelation(chapterReward);
                log.debug("Chapter advancement revelation: +{} -> now {}", chapterReward, session.getPlayer().getRevelation());

                currentMap = nextMap;
            }
        }

        // 地图切换后：移除旧地图的环境描写，生成从旧地图到新地图的过渡描写
        if (!session.getCurrentMapId().equals(mapIdBeforeTransition)) {
            outputs.removeIf(o -> o.startsWith(outputPrefix("environment")));
            MapConfig fromMap = dataLoader.getMap(mapIdBeforeTransition);
            if (currentMap != null) {
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
        for (int threshold : gameConfig().getSanityWarningThresholds()) {
            if (sanity <= threshold && !session.getTriggeredSanityWarnings().contains(threshold)) {
                session.getTriggeredSanityWarnings().add(threshold);
                String warning = director.sanityWarning(session, currentMap, story);
                String warnStripped = stateManager.stripInternal(warning);
                if (!warnStripped.isBlank()) {
                    outputs.add(outputPrefix("innerVoice") + warnStripped);
                    session.addChatMessage(new GameSession.ChatMessage("DIRECTOR_AI", null, warnStripped));
                }
                stateManager.applyControlTags(session, warning, AgentType.DIRECTOR);
            }
        }

        // ---- 步骤10：结局判定 ----
        // 导演AI根据理智值和揭露度判定是否触发结局，若触发则生成结局内容并终止游戏
        String endingType = director.determineEndingAction(session, story);
        if (endingType != null) {
            handleEnding(session, endingType, story, protagonistConfig, outputs);
        }

        return String.join("\n\n", outputs);
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

        MapConfig startMap = dataLoader.getMap(gameConfig().getStartingMapId());
        String opening = director.openingMonologue(story, startMap);
        String stripped = stateManager.stripInternal(opening);
        List<String> tags = stateManager.extractTags(opening);
        // 安全过滤：开场阶段禁止切换地图/章节/触发结局
        List<String> safeTags = tags.stream()
                .filter(t -> !t.startsWith("MAP:") && !t.startsWith("CHAPTER:") && !t.startsWith("ENDING:"))
                .toList();
        stateManager.executeTags(session, safeTags, AgentType.DIRECTOR);

        return outputPrefix("directorOpening") + "\n" + stripped;
    }

    /**
     * 处理开场阶段 —— 玩家首次输入后触发柚子登场。
     *
     * <p>开场阶段（gamePhase=opening）只执行一次，之后切换到 playing 阶段。</p>
     */
    public String processOpening(GameSession session) {
        StoryConfig story = dataLoader.getStory();
        session.incrementTurn();
        applySanityDecay(session, story);

        ProtagonistConfig config = dataLoader.getProtagonist();
        MapConfig currentMap = dataLoader.getMap(session.getCurrentMapId());
        // 防御性检查：如果 currentMapId 被 LLM 篡改为无效值，重置到初始地图
        if (currentMap == null) {
            log.error("Invalid currentMapId: {}, resetting to {}", session.getCurrentMapId(), gameConfig().getStartingMapId());
            session.setCurrentMapId(gameConfig().getStartingMapId());
            currentMap = dataLoader.getMap(gameConfig().getStartingMapId());
        }

        String yuzuIntro = protagonist.opening(session, config, currentMap);
        String stripped = stateManager.stripInternal(yuzuIntro);
        session.addChatMessage(new GameSession.ChatMessage("PROTAGONIST_AI", null, stripped));
        stateManager.applyControlTags(session, yuzuIntro, AgentType.PROTAGONIST);

        // 开场结束，进入正常游戏阶段
        session.setGamePhase("playing");

        return outputPrefix("protagonist") + stripped;
    }

    /**
     * 判断是否应检查地图切换。
     *
     * <p>条件：当前地图有下一地图 && 当前地图的谜题已解决或已失败。</p>
     */
    private boolean shouldCheckMapTransition(GameSession session, MapConfig currentMap) {
        if (currentMap.getNextMapId() == null || currentMap.getNextMapId().isBlank()) {
            return false;
        }
        boolean anyPuzzleSolved = currentMap.getPuzzles() != null &&
                currentMap.getPuzzles().stream().anyMatch(session::isPuzzleSolved);
        boolean anyPuzzleFailed = currentMap.getPuzzles() != null &&
                currentMap.getPuzzles().stream().anyMatch(p -> session.getFailedPuzzles().contains(p));
        return anyPuzzleSolved || anyPuzzleFailed;
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
        for (NpcConfig npc : dataLoader.getNpcs()) {
            if (npc.getName().equals(name)) {
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

        String yuzuEnding = protagonist.endingLine(session.getPlayer(), pConfig, endingType);
        String yuzuEndingStripped = stateManager.stripInternal(yuzuEnding);
        outputs.add(outputPrefix("protagonistFinal") + yuzuEndingStripped);

        session.setEnded(true);
        session.setEndingType(endingType);
        stateManager.applyControlTags(session, ending, AgentType.DIRECTOR);
        // 清理该会话的所有谜题AI记忆
        puzzleAI.clearSessionMemory(session.getSessionId());
    }
}
