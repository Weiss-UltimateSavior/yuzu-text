package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PuzzleAI extends BaseAgent {

    private final GameDataLoader dataLoader;

    public PuzzleAI(LlmService llm, GameDataLoader dataLoader) {
        super(llm);
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.PuzzlePrompts prompts() {
        return dataLoader.getPrompts().getPuzzle();
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    public String handle(GameSession session, PuzzleConfig puzzle, String playerMessage) {
        if (session == null || puzzle == null) return "";

        // Z3 修复：达到最大尝试次数时硬性阻止，不依赖 AI 输出标签
        int maxAttempts = puzzle.getMaxAttempts() > 0 ? puzzle.getMaxAttempts() : gameConfig().getPuzzleMaxAttempts();
        int attempts = session.getPuzzleAttempts(puzzle.getId());
        if (attempts >= maxAttempts) {
            // 使用 <ctrl> 块包裹标签，确保 stripInternal 正确剥离、
            // applyControlTags 正确解析，且 \S+ 不会误匹配方括号
            return "<ctrl>PUZZLE:FAIL:" + puzzle.getId() + "</ctrl> 谜题尝试次数已达上限。";
        }

        String prompt = buildPrompt(session, puzzle);

        // 首次进入谜题时，注入从地图探索过渡到解谜的提示
        List<PuzzleMemoryEntry> memory = session.getPuzzleMemoryEntries(puzzle.getId());
        if (memory.isEmpty()) {
            prompt = appendMapTransitionHint(session, prompt);
        }

        List<Map<String, String>> history = buildPuzzleHistory(session, puzzle);
        return chatWithSession(session, prompt, nullToEmpty(playerMessage), history.isEmpty() ? null : history);
    }

    private List<Map<String, String>> buildPuzzleHistory(GameSession session, PuzzleConfig puzzle) {
        List<PuzzleMemoryEntry> memory = session.getPuzzleMemoryEntries(puzzle.getId());

        // 始终注入最近的全局上下文（MapAI 描写、NPC 对话等），
        // 确保谜题 AI 和地图 AI 之间无缝切换
        boolean hasPuzzleMemory = !memory.isEmpty();
        List<Map<String, String>> globalContext = buildRecentGlobalContext(session, hasPuzzleMemory);

        if (!hasPuzzleMemory) {
            // 首次进入谜题，仅使用全局上下文
            return globalContext;
        }

        // 合并：全局上下文（最近几条） + 谜题专属记忆
        List<Map<String, String>> history = new ArrayList<>(globalContext);
        for (PuzzleMemoryEntry entry : memory) {
            history.add(Map.of("role", entry.role(), "content", entry.content()));
        }
        return history;
    }

    /**
     * 构建最近的全局上下文，供谜题 AI 了解当前环境。
     * 取最近 N 条全局消息（MapAI 描写、NPC 对话等），
     * 使谜题 AI 在解谜过程中仍能感知周围环境变化。
     */
    private List<Map<String, String>> buildRecentGlobalContext(GameSession session, boolean excludePuzzleAi) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        // 使用完整的 puzzleAiHistoryLimit 作为上下文长度，
        // 确保谜题 AI 拥有与地图 AI 同等的全局上下文感知能力，
        // 避免因上下文不足导致解谜回合无限拉长
        int maxMessages = gameConfig().getPuzzleAiHistoryLimit() > 0
                ? gameConfig().getPuzzleAiHistoryLimit()
                : gameConfig().getMapAiHistoryLimit();
        int start = Math.max(0, allMessages.size() - maxMessages);
        List<GameSession.ChatMessage> recent = new ArrayList<>(allMessages.subList(start, allMessages.size()));

        List<Map<String, String>> history = new ArrayList<>();
        for (GameSession.ChatMessage msg : recent) {
            // 当有 puzzleMemory 时，跳过 PUZZLE_AI 消息避免重复
            if (excludePuzzleAi && "PUZZLE_AI".equals(msg.senderType())) continue;

            String role;
            String content;

            switch (msg.senderType()) {
                case "PLAYER" -> {
                    role = "user";
                    content = msg.content();
                }
                case "PUZZLE_AI" -> {
                    role = "assistant";
                    content = msg.content();
                }
                case "MAP_AI" -> {
                    role = "assistant";
                    content = msg.content();
                }
                case "PROTAGONIST_AI" -> {
                    role = "user";
                    content = "【柚子】" + nullToEmpty(msg.content());
                }
                case "NPC_AI" -> {
                    role = "user";
                    content = "【" + (msg.npcId() != null ? msg.npcId() : "未知") + "】" + nullToEmpty(msg.content());
                }
                case "DIRECTOR_AI" -> {
                    role = "user";
                    content = "【导演旁白】" + nullToEmpty(msg.content());
                }
                default -> {
                    role = "user";
                    content = msg.content();
                }
            }

            if (content != null && !content.isBlank()) {
                history.add(Map.of("role", role, "content", content));
            }
        }

        return history;
    }

    private String buildPrompt(GameSession session, PuzzleConfig puzzle) {
        StringBuilder sb = new StringBuilder();
        appendNonNull(sb, puzzle.getSystemPrompt());

        int maxAttempts = puzzle.getMaxAttempts() > 0 ? puzzle.getMaxAttempts() : gameConfig().getPuzzleMaxAttempts();

        // 替换 stateHeader 模板中的占位符
        String stateHeader = safeReplace(prompts().getStateHeader(),
                "{puzzleName}", nullToEmpty(puzzle.getName()),
                "{difficulty}", String.valueOf(puzzle.getDifficulty()),
                "{description}", nullToEmpty(puzzle.getDescription()),
                "{solutionCriteria}", nullToEmpty(puzzle.getSolutionCriteria()),
                "{maxAttempts}", String.valueOf(maxAttempts),
                "{attempts}", String.valueOf(session.getPuzzleAttempts(puzzle.getId())),
                "{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()),
                "{failNarrative}", nullToEmpty(puzzle.getFailNarrative()));
        sb.append("\n\n").append(stateHeader).append("\n");
        sb.append("谜题ID: ").append(nullToEmpty(puzzle.getId())).append("\n");

        sb.append("\n=== 当前状态 ===\n");
        sb.append("回合: ").append(session.getTurn()).append("\n");
        sb.append("已尝试次数: ").append(session.getPuzzleAttempts(puzzle.getId())).append("\n");
        sb.append("最大尝试次数: ").append(maxAttempts).append("\n");

        // 前置物品
        String requiredItemId = puzzle.getRequiredItemId();
        if (requiredItemId != null && !requiredItemId.isBlank()) {
            String displayName = resolveItemName(session, requiredItemId);
            boolean owned = session.getPlayer().hasItem(requiredItemId) || session.yuzuHasItem(requiredItemId);
            sb.append(nullToEmpty(prompts().getRequiredItemTemplate())
                    .replace("{itemId}", requiredItemId)
                    .replace("{itemName}", displayName)
                    .replace("{status}", owned ? nullToEmpty(prompts().getOwnedLabel()) : nullToEmpty(prompts().getMissingLabel())))
              .append("\n");
        }

        // 计算最少有效交互轮次（难度 × 2，确保谜题不会太快被跳过）
        int minRounds = puzzle.getDifficulty() * 2;
        int currentRounds = session.getPuzzleAttempts(puzzle.getId());
        int failSanityPenalty = puzzle.getFailSanityPenalty();

        // 构建未解锁 NPC 列表（供 NPC:UNLOCK 标签参考）
        String npcList = buildUnlockedNpcList(session, puzzle);

        // 替换模板中的占位符
        String solvingRules = safeReplace(prompts().getSolvingRules(),
                "{minRounds}", String.valueOf(minRounds),
                "{difficulty}", String.valueOf(puzzle.getDifficulty()),
                "{currentRounds}", String.valueOf(currentRounds),
                "{maxAttempts}", String.valueOf(maxAttempts),
                "{failSanityPenalty}", String.valueOf(failSanityPenalty));

        String availableTags = safeReplace(prompts().getAvailableTagsTemplate(),
                "{puzzleId}", nullToEmpty(puzzle.getId()));

        String itemTakeTag = requiredItemId != null && !requiredItemId.isBlank()
                ? safeReplace(prompts().getItemTakeTagTemplate(), "{itemId}", requiredItemId)
                : null;

        String npcUnlockSection = safeReplace(prompts().getNpcUnlockSection(),
                "{npcList}", npcList);

        String ctrlTagExample = safeReplace(prompts().getCtrlTagExample(),
                "{puzzleId}", nullToEmpty(puzzle.getId()));

        // 找到当前地图中第一个未解锁的 NPC 作为示例
        String exampleNpcId = findFirstUnlockedNpcId(session, puzzle);
        String solveWithNpcExample = safeReplace(prompts().getSolveWithNpcExample(),
                "{puzzleId}", nullToEmpty(puzzle.getId()),
                "{npcId}", exampleNpcId != null ? exampleNpcId : "npc_id");

        appendSection(sb, solvingRules);
        appendSection(sb, availableTags);
        appendSection(sb, itemTakeTag);
        appendSection(sb, npcUnlockSection);
        appendSection(sb, ctrlTagExample);
        appendSection(sb, solveWithNpcExample);

        return sb.toString();
    }

    private String resolveItemName(GameSession session, String itemId) {
        String dynamicName = session.getDynamicItemName(itemId);
        if (dynamicName != null) return dynamicName;
        ItemConfig item = dataLoader.getItem(itemId);
        return item != null ? nullToEmpty(item.getName()) : itemId;
    }

    /**
     * 构建当前地图中尚未解锁的 NPC 列表，供 NPC:UNLOCK 标签参考。
     * 格式：npcId(中文名称)，如 "npc_maintenance(维修工), npc_abyss_singer(深渊歌者)"
     */
    private String buildUnlockedNpcList(GameSession session, PuzzleConfig puzzle) {
        MapConfig map = dataLoader.getMap(puzzle.getMapId());
        if (map == null || map.getNpcIds() == null || map.getNpcIds().isEmpty()) {
            return "无";
        }

        List<String> locked = new ArrayList<>();
        for (String npcId : map.getNpcIds()) {
            if (!session.isNpcUnlocked(npcId)) {
                NpcConfig npc = dataLoader.getNpc(npcId);
                String displayName = npc != null ? nullToEmpty(npc.getName()) : npcId;
                locked.add(npcId + "(" + displayName + ")");
            }
        }

        return locked.isEmpty() ? "无（当前地图所有NPC已解锁）" : String.join(", ", locked);
    }

    /**
     * 找到当前地图中第一个未解锁的 NPC ID，作为示例中的占位符。
     */
    private String findFirstUnlockedNpcId(GameSession session, PuzzleConfig puzzle) {
        MapConfig map = dataLoader.getMap(puzzle.getMapId());
        if (map == null || map.getNpcIds() == null) return null;
        for (String npcId : map.getNpcIds()) {
            if (!session.isNpcUnlocked(npcId)) {
                return npcId;
            }
        }
        return null;
    }

    /**
     * 首次进入谜题时，注入从地图探索过渡到解谜的提示，
     * 让谜题 AI 的输出与之前的 MapAI 描写自然衔接。
     */
    private String appendMapTransitionHint(GameSession session, String prompt) {
        List<GameSession.ChatMessage> history = session.getChatHistory();
        if (history == null || history.isEmpty()) return prompt;

        // 查找最近一条 MapAI 的输出，提取环境描写上下文
        String lastMapDescription = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            GameSession.ChatMessage msg = history.get(i);
            if ("MAP_AI".equals(msg.senderType())) {
                lastMapDescription = msg.content();
                break;
            }
        }

        if (lastMapDescription != null) {
            // 截取最后一段描写作为衔接参考（避免过长）
            String context = lastMapDescription.length() > 200
                    ? lastMapDescription.substring(lastMapDescription.length() - 200)
                    : lastMapDescription;
            return prompt + "\n\n=== 探索→解谜过渡 ===\n玩家从探索状态转入解谜。以下是最近的环境描写片段：「" + context + "」\n请从这段环境描写自然过渡到谜题场景，不要重复已有描写，而是聚焦于谜题相关的细节。\n";
        }

        return prompt;
    }
}
