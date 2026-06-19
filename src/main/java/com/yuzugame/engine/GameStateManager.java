package com.yuzugame.engine;

import com.yuzugame.model.GameConfig;
import com.yuzugame.model.GameSession;
import com.yuzugame.model.ItemConfig;
import com.yuzugame.model.PuzzleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 游戏状态管理器 —— 控制标签（Game DSL）的解析、权限校验与执行。
 *
 * <p>本类是游戏"控制标签系统"的核心，负责：
 * <ul>
 *   <li>从 AI 输出中提取控制标签（支持 {@code <ctrl>} 块和裸标签两种格式）</li>
 *   <li>基于 Agent 类型进行标签权限校验（如 MapAI 不能触发 PUZZLE:SOLVE）</li>
 *   <li>执行标签对应的游戏状态变更（理智、揭露度、物品、NPC、地图切换等）</li>
 *   <li>从输出文本中剥离内部标签和裸标签，确保玩家只看到叙事内容</li>
 * </ul></p>
 *
 * <p>标签格式：{@code CATEGORY:ACTION:PARAM}，例如 {@code SANITY:-3}、{@code PUZZLE:SOLVE:puzzle_sewer_pipes}</p>
 *
 * @see AgentType 各 Agent 的权限定义
 */
@Component
public class GameStateManager {

    private static final Logger log = LoggerFactory.getLogger(GameStateManager.class);

    private final GameDataLoader dataLoader;

    public GameStateManager(GameDataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    /** 匹配 <ctrl>...</ctrl> 块中的控制标签 */
    private static final Pattern CTRL_BLOCK = Pattern.compile("<ctrl>(.*?)</ctrl>", Pattern.DOTALL);

    /** 匹配 <internal>...</internal> 块（AI内部判断，不展示给玩家） */
    private static final Pattern INTERNAL_BLOCK = Pattern.compile("<internal>(.*?)</internal>", Pattern.DOTALL);

    /**
     * 裸标签匹配 —— 当 LLM 未使用 {@code <ctrl>} 块包裹标签时的兜底提取。
     *
     * <p>匹配格式如：{@code PUZZLE:SOLVE:id}、{@code SANITY:-3}、{@code MAP:id} 等。
     * 仅在 {@code <ctrl>} 块提取结果为空时启用，避免重复提取。</p>
     */
    private static final Pattern BARE_TAG = Pattern.compile("(PUZZLE:(?:SOLVE|FAIL|ACTIVATE):\\S+|SANITY:[+-]?\\d+|REVELATION:[+-]?\\d+|AFFECTION:[+-]?\\d+|MAP:\\S+|CHAPTER:\\S+|ITEM:(?:GIVE|TAKE|CREATE|USE):\\S+|ITEM:FOUND:\\S+|NPC:(?:UNLOCK|KILL|REVIVE):\\S+|NPC_AFFECTION:\\S+:[+-]?\\d+|ENDING:\\S+|AREA:\\S+)");

    /**
     * Agent 类型枚举 —— 用于控制标签的权限校验。
     *
     * <p>每种 Agent 只能执行其职责范围内的标签，防止越权操作。
     * 例如 MapAI 不能触发 PUZZLE:SOLVE，PuzzleAI 不能切换地图。</p>
     */
    public enum AgentType {
        PROTAGONIST, MAP, NPC, DIRECTOR, PUZZLE
    }

    /**
     * 从 AI 输出中提取控制标签并执行。
     *
     * <p>这是 GameEngine 调用最频繁的方法，每次 AI 回复后都会调用。</p>
     *
     * @param session 当前游戏会话
     * @param aiOutput AI 的原始输出文本
     * @param agent 产生此输出的 Agent 类型
     */
    public void applyControlTags(GameSession session, String aiOutput, AgentType agent) {
        if (aiOutput == null || aiOutput.isBlank()) {
            return;
        }
        List<String> tags = extractTags(aiOutput);
        executeTags(session, tags, agent);
    }

    /** 批量执行控制标签列表 */
    public void executeTags(GameSession session, List<String> tags, AgentType agent) {
        for (String tag : tags) {
            executeTag(session, tag, agent);
        }
    }

    /**
     * 从输出文本中剥离所有内部标签和控制标签，返回纯叙事文本。
     *
     * <p>剥离顺序：{@code <internal>} 块 → {@code <ctrl>} 块 → 裸标签。
     * 确保玩家看到的输出中不包含任何 DSL 标记。</p>
     */
    public String stripInternal(String text) {
        if (text == null) return "";
        String result = INTERNAL_BLOCK.matcher(text).replaceAll("");
        result = CTRL_BLOCK.matcher(result).replaceAll("");
        result = BARE_TAG.matcher(result).replaceAll("");
        return result.trim();
    }

    /**
     * 从 AI 输出中提取控制标签列表。
     *
     * <p>优先提取 {@code <ctrl>} 块中的标签；若 {@code <ctrl>} 块为空，
     * 则回退到裸标签匹配（容错机制，应对 LLM 不遵守格式要求的情况）。</p>
     */
    public List<String> extractTags(String text) {
        List<String> tags = new ArrayList<>();
        Matcher m = CTRL_BLOCK.matcher(text);
        while (m.find()) {
            String block = m.group(1).trim();
            Matcher tagMatcher = BARE_TAG.matcher(block);
            while (tagMatcher.find()) {
                tags.add(tagMatcher.group(1));
            }
            if (tags.isEmpty()) {
                for (String line : block.split("\\n")) {
                    line = line.trim();
                    if (!line.isEmpty() && line.contains(":") && line.split(":").length >= 2) {
                        tags.add(line);
                    }
                }
            }
        }
        if (tags.isEmpty()) {
            Matcher bareMatcher = BARE_TAG.matcher(text);
            while (bareMatcher.find()) {
                tags.add(bareMatcher.group(1));
            }
        }
        return tags;
    }

    /**
     * Agent 权限校验 —— 判断指定 Agent 是否有权执行某类标签。
     *
     * <p>权限矩阵：
     * <ul>
     *   <li>PROTAGONIST（柚子）：SANITY/REVELATION/AFFECTION + ITEM:GIVE/CREATE/USE</li>
     *   <li>MAP（地图AI）：SANITY/REVELATION/AFFECTION + ITEM:FOUND + NPC:UNLOCK + PUZZLE:ACTIVATE</li>
     *   <li>NPC：SANITY/REVELATION/AFFECTION + ITEM:GIVE（第3次对话给道具）</li>
     *   <li>DIRECTOR（导演）：SANITY/REVELATION/AFFECTION/NPC_AFFECTION + ITEM:GIVE/CREATE/USE/TAKE + NPC:KILL/REVIVE + PUZZLE:ACTIVATE + ENDING/EVENT（地图/章节切换由引擎处理，不由标签触发）</li>
     *   <li>PUZZLE：SANITY/REVELATION/AFFECTION + ITEM:TAKE + PUZZLE:SOLVE/FAIL + NPC:UNLOCK</li>
     * </ul></p>
     */
    private boolean hasPermission(AgentType agent, String category, String action) {
        return switch (agent) {
            case PROTAGONIST -> switch (category) {
                case "SANITY", "REVELATION", "AFFECTION" -> true;
                case "ITEM" -> switch (action) {
                    case "GIVE", "CREATE", "USE" -> true;
                    default -> false;
                };
                default -> false;
            };
            case MAP -> switch (category) {
                case "SANITY", "REVELATION", "AFFECTION" -> true;
                case "ITEM" -> "FOUND".equals(action);
                case "PUZZLE" -> "ACTIVATE".equals(action);
                case "AREA" -> true;
                default -> false;
            };
            case NPC -> switch (category) {
                case "SANITY", "REVELATION", "AFFECTION" -> true;
                case "ITEM" -> "GIVE".equals(action);
                default -> false;
            };
            case DIRECTOR -> switch (category) {
                case "SANITY", "REVELATION", "AFFECTION", "NPC_AFFECTION" -> true;
                case "ITEM" -> switch (action) {
                    case "GIVE", "CREATE", "USE", "TAKE" -> true;
                    default -> false;
                };
                case "NPC" -> switch (action) {
                    case "KILL", "REVIVE" -> true;
                    default -> false;
                };
                case "PUZZLE" -> "ACTIVATE".equals(action);
                case "MAP", "CHAPTER" -> false;
                case "ENDING", "EVENT" -> true;
                default -> false;
            };
            case PUZZLE -> switch (category) {
                case "SANITY", "REVELATION", "AFFECTION" -> true;
                case "ITEM" -> "TAKE".equals(action);
                case "NPC" -> "UNLOCK".equals(action);
                case "PUZZLE" -> "SOLVE".equals(action) || "FAIL".equals(action);
                default -> false;
            };
        };
    }

    /**
     * 执行单个控制标签 —— 解析标签格式、校验权限、分发到具体处理方法。
     *
     * <p>标签格式：{@code CATEGORY:ACTION:PARAM}，最多3段（PARAM 可含冒号，如 ITEM:FOUND:id:中文名称）。
     * 例如 {@code ITEM:GIVE:item_pipe_sample}、{@code ITEM:FOUND:strange_model_base:奇异模型底座}、{@code NPC_AFFECTION:npc_maintenance:+5}</p>
     */
    private String executeTag(GameSession session, String tag, AgentType agent) {
        String[] parts = tag.split(":", 3);

        if (parts.length < 2) {
            log.warn("Invalid tag format: {}", tag);
            return null;
        }

        String category = parts[0];
        String action = parts.length > 1 ? parts[1] : "";
        String param = parts.length > 2 ? parts[2] : "";

        if (!hasPermission(agent, category, action)) {
            log.warn("Agent {} does not have permission for tag: {}", agent, tag);
            return null;
        }

        try {
            return switch (category) {
                case "SANITY" -> handleSanity(session, action);
                case "REVELATION" -> handleRevelation(session, action);
                case "AFFECTION" -> handleAffection(session, action);
                case "NPC_AFFECTION" -> handleNpcAffection(session, action, param);
                case "ITEM" -> handleItem(session, action, param, agent);
                case "NPC" -> handleNpc(session, action, param);
                case "PUZZLE" -> handlePuzzle(session, action, param);
                case "MAP" -> handleMap(session, action);
                case "CHAPTER" -> handleChapter(session, action);
                case "ENDING" -> handleEnding(session, action);
                case "EVENT" -> handleEvent(session, action);
                case "AREA" -> handleArea(session, action);
                default -> null;
            };
        } catch (Exception e) {
            log.error("Error executing tag {}: {}", tag, e.getMessage());
            return null;
        }
    }

    /** 处理 SANITY:Δ 标签 —— 修改玩家理智值（Δ为正负整数） */
    private String handleSanity(GameSession session, String deltaStr) {
        Integer delta = parseDelta(deltaStr);
        if (delta == null) return null;
        session.getPlayer().addSanity(delta);
        log.debug("Sanity {} -> now {}", delta > 0 ? "+" + delta : delta, session.getPlayer().getSanity());
        return null;
    }

    /** 处理 REVELATION:Δ 标签 —— 修改揭露度 */
    private String handleRevelation(GameSession session, String deltaStr) {
        Integer delta = parseDelta(deltaStr);
        if (delta == null) return null;
        session.getPlayer().addRevelation(delta);
        log.debug("Revelation {} -> now {}", delta > 0 ? "+" + delta : delta, session.getPlayer().getRevelation());
        return null;
    }

    /** 处理 AFFECTION:Δ 标签 —— 修改柚子好感度 */
    private String handleAffection(GameSession session, String deltaStr) {
        Integer delta = parseDelta(deltaStr);
        if (delta == null) return null;
        session.getPlayer().addAffection(delta);
        log.debug("Affection {} -> now {}", delta > 0 ? "+" + delta : delta, session.getPlayer().getAffection());
        return null;
    }

    private Integer parseDelta(String deltaStr) {
        if (deltaStr == null || deltaStr.isBlank()) {
            log.warn("Delta value is empty");
            return null;
        }
        try {
            return Integer.parseInt(deltaStr.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid delta value: {}", deltaStr);
            return null;
        }
    }

    /** 处理 NPC_AFFECTION:npcId:Δ 标签 —— 修改指定NPC的对话计数（兼容旧标签） */
    private String handleNpcAffection(GameSession session, String npcId, String deltaStr) {
        if (npcId == null || npcId.isBlank()) {
            log.warn("NPC_AFFECTION tag missing npcId");
            return null;
        }
        Integer delta = parseDelta(deltaStr);
        if (delta == null) return null;
        for (int i = 0; i < Math.abs(delta); i++) {
            if (delta > 0) session.incrementNpcDialogueCount(npcId);
        }
        log.debug("NPC {} dialogue count adjusted by {} -> now {}", npcId, delta, session.getNpcDialogueCount(npcId));
        return null;
    }

    /**
     * 处理 ITEM:ACTION:PARAM 标签 —— 物品操作。
     *
     * <ul>
     *   <li>GIVE — NPC/柚子将物品交给玩家。格式：{@code ITEM:GIVE:物品ID:中文名称}
     *       （中文名称可选，缺失时用物品ID兜底并打印warn日志）</li>
     *   <li>FOUND — 场景中出现可拾取物品。格式：{@code ITEM:FOUND:物品ID:中文名称}
     *       （中文名称可选，缺失时用物品ID兜底）</li>
     *   <li>TAKE — 从玩家背包中移除物品。格式：{@code ITEM:TAKE:物品ID}</li>
     *   <li>CREATE — 柚子制造物品。格式：{@code ITEM:CREATE:物品ID}</li>
     *   <li>USE — 柚子消耗物品。格式：{@code ITEM:USE:物品ID}</li>
     * </ul>
     *
     * <p>物品ID命名规范：{@code item_{来源}_{用途}}，如 {@code item_maintenance_gift}、{@code item_pipe_sample}。</p>
     */
    private String handleItem(GameSession session, String action, String param, AgentType agent) {
        if (param == null || param.isBlank()) {
            log.warn("ITEM:{} tag missing param", action);
            return null;
        }
        return switch (action) {
            case "GIVE" -> {
                String[] giveParts = param.split(":", 2);
                String itemId = giveParts[0];
                if (itemId.isBlank()) {
                    log.warn("ITEM:GIVE tag missing itemId");
                    yield null;
                }
                String itemName = giveParts.length > 1 ? giveParts[1] : null;
                if (itemName == null || itemName.isBlank()) {
                    log.warn("ITEM:GIVE tag missing Chinese name: ITEM:GIVE:{} — expected format ITEM:GIVE:id:中文名称", itemId);
                    itemName = itemId;
                }
                if (agent == AgentType.PROTAGONIST) {
                    if (!session.yuzuHasItem(itemId)) {
                        log.warn("ProtagonistAI ITEM:GIVE rejected: Yuzu does not have item {} — use ITEM:CREATE first", itemId);
                        yield null;
                    }
                    session.removeYuzuItem(itemId);
                } else {
                    if (session.yuzuHasItem(itemId)) {
                        session.removeYuzuItem(itemId);
                    }
                }
                session.getPlayer().addItem(itemId);
                if (dataLoader.getItem(itemId) == null) {
                    session.registerDynamicItemName(itemId, itemName);
                }
                log.debug("Item given to player: {} ({}) by {}", itemId, itemName, agent);
                yield null;
            }
            case "FOUND" -> {
                String[] itemParts = param.split(":", 2);
                String itemId = itemParts[0];
                if (itemId.isBlank()) {
                    log.warn("ITEM:FOUND tag missing itemId");
                    yield null;
                }
                String itemName = itemParts.length > 1 ? itemParts[1] : null;
                if (itemName == null || itemName.isBlank()) {
                    log.warn("ITEM:FOUND tag missing Chinese name: ITEM:FOUND:{} — expected format ITEM:FOUND:id:中文名称", itemId);
                    itemName = itemId;
                }
                session.foundItem(itemId);
                session.getPlayer().addItem(itemId);
                if (dataLoader.getItem(itemId) == null) {
                    session.registerDynamicItemName(itemId, itemName);
                }
                log.debug("Item found and picked up: {} ({})", itemId, itemName);
                yield null;
            }
            case "TAKE" -> {
                session.getPlayer().removeItem(param);
                log.debug("Item taken from player: {}", param);
                yield null;
            }
            case "CREATE" -> {
                session.addYuzuItem(param);
                log.debug("Item created by Yuzu: {}", param);
                yield null;
            }
            case "USE" -> {
                if (!session.yuzuHasItem(param)) {
                    log.warn("ITEM:USE rejected: Yuzu does not have item {}", param);
                    yield null;
                }
                if ("item_sedative".equals(param) && session.getPlayer().getSanity() >= 20) {
                    log.warn("ITEM:USE rejected: sedative can only be used when player sanity < 20, current sanity: {}", session.getPlayer().getSanity());
                    yield null;
                }
                if ("item_sedative".equals(param) && session.getTurn() >= 60) {
                    log.warn("ITEM:USE rejected: sedative can only be used before turn 60, current turn: {}", session.getTurn());
                    yield null;
                }
                session.removeYuzuItem(param);
                ItemConfig usedItem = dataLoader.getItem(param);
                if (usedItem != null && usedItem.getSanityRecovery() != null) {
                    session.getPlayer().addSanity(usedItem.getSanityRecovery());
                    log.debug("Item used by Yuzu: {} → sanity +{} -> now {}", param, usedItem.getSanityRecovery(), session.getPlayer().getSanity());
                } else {
                    log.debug("Yuzu used item: {}", param);
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 处理 NPC:ACTION:ID 标签 —— NPC状态变更。
     *
     * <ul>
     *   <li>UNLOCK — 解锁NPC对话</li>
     *   <li>KILL — NPC死亡</li>
     *   <li>REVIVE — NPC复活</li>
     * </ul>
     */
    private String handleNpc(GameSession session, String action, String param) {
        if (param == null || param.isBlank()) {
            log.warn("NPC:{} tag missing npcId", action);
            return null;
        }
        return switch (action) {
            case "UNLOCK" -> {
                boolean firstUnlock = !session.isNpcUnlocked(param);
                session.unlockNpc(param);
                if (firstUnlock) {
                    int reward = gameConfig().getNpcUnlockSanityReward();
                    session.getPlayer().addSanity(reward);
                    log.debug("NPC unlocked (first time): {}, sanity +{} -> now {}", param, reward, session.getPlayer().getSanity());
                } else {
                    log.debug("NPC unlocked (already): {}", param);
                }
                yield null;
            }
            case "KILL" -> {
                session.killNpc(param);
                log.debug("NPC killed: {}", param);
                yield null;
            }
            case "REVIVE" -> {
                session.reviveNpc(param);
                log.debug("NPC revived: {}", param);
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 处理 PUZZLE:ACTION:ID 标签 —— 谜题状态变更。
     *
     * <ul>
     *   <li>ACTIVATE — 激活谜题（已解决/已失败的谜题忽略）</li>
     *   <li>SOLVE — 标记谜题已解决，清除活跃谜题ID</li>
     *   <li>FAIL — 标记谜题失败，递增尝试次数，清除活跃谜题ID</li>
     * </ul>
     */
    private String handlePuzzle(GameSession session, String action, String param) {
        if (param == null || param.isBlank()) {
            log.warn("PUZZLE:{} called with empty puzzle ID", action);
            return null;
        }

        return switch (action) {
            case "ACTIVATE" -> {
                if (session.isPuzzleSolved(param)) {
                    log.warn("Puzzle {} already solved, ignoring ACTIVATE", param);
                    yield null;
                }
                if (session.getFailedPuzzles().contains(param)) {
                    log.warn("Puzzle {} already failed, ignoring ACTIVATE", param);
                    yield null;
                }
                session.setActivePuzzleId(param);
                log.debug("Puzzle activated: {}", param);
                yield null;
            }
            case "SOLVE" -> {
                // attempts 已在 PuzzleAI.handle() 中递增，此处不再重复递增
                session.markPuzzleSolved(param);
                session.setActivePuzzleId(null);
                log.debug("Puzzle solved: {} (attempts: {})", param, session.getPuzzleAttempts(param));
                yield null;
            }
            case "FAIL" -> {
                // 谜题不再有最大尝试次数限制，FAIL 仅表示本次尝试失败
                // 谜题保持活跃，玩家可以继续尝试直到成功
                log.debug("Puzzle attempt failed: {} — puzzle remains active, player can retry", param);
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 地图切换 —— 统一的状态重置入口。
     *
     * <p>无论是 Step8 出口+移动关键词路径，还是 MAP:mapId 标签路径，
     * 所有地图切换的状态重置逻辑都通过此方法执行，确保两条路径行为一致。</p>
     *
     * <p>状态重置清单：
     * <ul>
     *   <li>currentMapId → 新地图ID</li>
     *   <li>exitUnlocked → false</li>
     *   <li>mapAutoTriggered → false（过渡描写由 GameEngine 统一生成）</li>
     *   <li>mapEntryTurn → 当前回合</li>
     *   <li>currentArea → null</li>
     *   <li>activePuzzleId → null</li>
     *   <li>currentChapter → 根据地图配置更新</li>
     * </ul></p>
     *
     * @param session 当前游戏会话
     * @param mapId 目标地图ID
     * @return 切换成功返回 mapId，地图不存在返回 null
     */
    public String handleMap(GameSession session, String mapId) {
        if (mapId == null || mapId.isBlank()) {
            log.warn("MAP tag missing mapId");
            return null;
        }
        mapId = mapId.trim().replaceAll("[^a-zA-Z0-9_]", "");
        com.yuzugame.model.MapConfig mapConfig = dataLoader.getMap(mapId);
        if (mapConfig == null) {
            log.warn("Map transition rejected: mapId {} not found", mapId);
            return null;
        }

        String oldMapId = session.getCurrentMapId();
        com.yuzugame.model.MapConfig oldMapConfig = dataLoader.getMap(oldMapId);
        if (oldMapConfig != null && oldMapConfig.getNextMapId() != null
                && !oldMapConfig.getNextMapId().isBlank()
                && !mapId.equals(oldMapConfig.getNextMapId())) {
            log.warn("Map transition rejected: target {} is not the next map in sequence (expected {} from current map {})",
                    mapId, oldMapConfig.getNextMapId(), oldMapId);
            return null;
        }

        session.setCurrentMapId(mapId);
        session.setExitUnlocked(false);
        session.setMapAutoTriggered(false);
        session.setMapEntryTurn(session.getTurn());
        session.setCurrentArea(null);
        session.setActivePuzzleId(null);

        if (mapConfig.getChapter() != null) {
            session.setCurrentChapter(mapConfig.getChapter());
        }

        log.debug("Map transitioned: {} -> {}, exit reset, area cleared, active puzzle cleared, chapter -> {}",
                oldMapId, mapId, session.getCurrentChapter());
        return mapId;
    }

    /** 处理 CHAPTER:chapterId 标签 —— 推进章节 */
    private String handleChapter(GameSession session, String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            log.warn("CHAPTER tag missing chapterId");
            return null;
        }
        chapterId = chapterId.trim().replaceAll("[^a-zA-Z0-9_]", "");
        session.setCurrentChapter(chapterId);
        log.debug("Chapter advanced to: {}", chapterId);
        return null;
    }

    /** 处理 ENDING:type 标签 —— 触发结局（FAIL/FINAL/PERFECT） */
    private String handleEnding(GameSession session, String endingType) {
        if (endingType == null || endingType.isBlank()) {
            log.warn("ENDING tag missing endingType");
            return null;
        }
        endingType = endingType.trim().replaceAll("[^a-zA-Z0-9_]", "");
        session.setEnded(true);
        session.setEndingType(endingType);
        session.clearAllPuzzleMemory();
        log.debug("Ending triggered: {}", endingType);
        return null;
    }

    /** 处理 EVENT:eventId 标签 —— 触发命名事件（预留扩展） */
    private String handleEvent(GameSession session, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("EVENT tag missing eventId");
            return null;
        }
        log.debug("Event triggered: {}", eventId);
        return null;
    }

    /** 处理 AREA:区域名称 标签 —— 更新玩家当前所在区域 */
    private String handleArea(GameSession session, String areaName) {
        if (areaName == null || areaName.isBlank()) {
            log.warn("AREA tag missing areaName");
            return null;
        }
        areaName = areaName.trim();
        session.setCurrentArea(areaName);
        log.debug("Player area updated to: {}", areaName);
        return null;
    }
}
