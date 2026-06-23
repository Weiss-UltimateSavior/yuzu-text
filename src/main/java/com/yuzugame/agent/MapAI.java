package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MapAI extends BaseAgent {

    private final GameDataLoader dataLoader;

    public MapAI(LlmService llm, GameDataLoader dataLoader) {
        super(llm);
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.MapPrompts prompts() {
        return dataLoader.getPrompts().getMap();
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    public String describe(GameSession session, MapConfig currentMap) {
        if (currentMap == null) return "";
        String prompt = buildPrompt(session, currentMap);

        // 检测是否刚从谜题退出，注入过渡提示实现无缝切换
        prompt = appendPuzzleTransitionHint(session, prompt);

        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, "[玩家正在观察周围环境]", history.isEmpty() ? null : history);
    }

    public String autoDescribe(GameSession session, MapConfig currentMap) {
        if (currentMap == null) return "";
        String prompt = buildPrompt(session, currentMap);
        String autoDesc = prompts().getAutoDescribePrompt();
        if (autoDesc != null) {
            prompt += "\n\n" + autoDesc;
        }
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, "[玩家首次进入 " + currentMap.getName() + "，提供环境描写]", history.isEmpty() ? null : history);
    }

    public String transitionDescribe(GameSession session, MapConfig fromMap, MapConfig toMap) {
        if (fromMap == null || toMap == null) return "";
        String prompt = buildPrompt(session, toMap);
        String template = prompts().getTransitionDescribeTemplate();
        if (template != null) {
            template = template
                    .replace("{fromMapName}", nullToEmpty(fromMap.getName()))
                    .replace("{fromMapDesc}", nullToEmpty(fromMap.getDescription()))
                    .replace("{fromExitHint}", nullToEmpty(fromMap.getExitHint()))
                    .replace("{toMapName}", nullToEmpty(toMap.getName()));
            prompt += "\n\n" + template;
        }
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, "[玩家从" + fromMap.getName() + "前往" + toMap.getName() + "，描写过渡场景]", history.isEmpty() ? null : history);
    }

    private List<Map<String, String>> buildMixedHistory(GameSession session) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        int maxMessages = gameConfig().getMapAiHistoryLimit();
        int start = Math.max(0, allMessages.size() - maxMessages);
        List<GameSession.ChatMessage> recent = new ArrayList<>(allMessages.subList(start, allMessages.size()));

        Map<String, String> labels = prompts().getChatHistoryLabels();
        List<Map<String, String>> history = new ArrayList<>();
        for (GameSession.ChatMessage msg : recent) {
            String role;
            String content;

            switch (msg.senderType()) {
                case "PLAYER" -> {
                    role = "user";
                    content = msg.content();
                }
                case "MAP_AI" -> {
                    role = "assistant";
                    content = msg.content();
                }
                case "NPC_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("NPC_AI", "{npcId}") : "{npcId}";
                    content = "【" + label.replace("{npcId}", msg.npcId() != null ? msg.npcId() : "未知") + "】" + msg.content();
                }
                case "PROTAGONIST_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("PROTAGONIST_AI", "柚子") : "柚子";
                    content = "【" + label + "】" + msg.content();
                }
                case "DIRECTOR_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("DIRECTOR_AI", "导演旁白") : "导演旁白";
                    content = "【" + label + "】" + msg.content();
                }
                case "PUZZLE_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("PUZZLE_AI", "谜题") : "谜题";
                    content = "【" + label + "】" + msg.content();
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

    private String buildPrompt(GameSession session, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder();
        appendNonNull(sb, currentMap.getSystemPrompt());

        List<Map<String, String>> areas = currentMap.getAreas();
        if (areas != null && !areas.isEmpty()) {
            sb.append("\n\n=== 区域划分 ===\n");
            int idx = 1;
            for (Map<String, String> area : areas) {
                sb.append(idx++).append(". ").append(nullToEmpty(area.get("name"))).append("：").append(nullToEmpty(area.get("description"))).append("\n");
            }
        }

        List<String> mapItems = currentMap.getItems() != null ? currentMap.getItems() : Collections.emptyList();
        List<String> mapNpcIds = currentMap.getNpcIds() != null ? currentMap.getNpcIds() : Collections.emptyList();
        List<String> mapPuzzles = currentMap.getPuzzles() != null ? currentMap.getPuzzles() : Collections.emptyList();

        sb.append("\n\n=== 场景状态 ===\n");
        sb.append("地图: ").append(nullToEmpty(currentMap.getName())).append("\n");
        sb.append("章节: ").append(nullToEmpty(currentMap.getChapterName())).append("\n");
        sb.append("气氛: ").append(nullToEmpty(currentMap.getAtmosphere())).append("\n");
        sb.append("当前回合: ").append(session.getTurn()).append(" (本地图内: ").append(session.getMapTurns()).append(")\n");
        sb.append("玩家理智: ").append(session.getPlayer().getSanity()).append("/100\n");
        if (session.getCurrentArea() != null) {
            sb.append("玩家当前位置: ").append(session.getCurrentArea()).append("\n");
        }
        int foundInMap = (int) mapItems.stream().filter(session::isItemFound).count();
        sb.append("已发现物品: ").append(foundInMap).append("/").append(mapItems.size()).append("\n");
        sb.append("已解锁NPC数: ").append(mapNpcIds.stream().filter(session::isNpcUnlocked).count()).append("/").append(mapNpcIds.size()).append("\n");

        for (String pId : mapPuzzles) {
            if (session.isPuzzleSolved(pId)) {
                sb.append("谜题 ").append(pId).append(": 已解决。出口:").append(nullToEmpty(currentMap.getExitHint())).append("\n");
            } else if (session.getFailedPuzzles().contains(pId)) {
                sb.append("谜题 ").append(pId).append(": 已失败。\n");
            } else if (session.getActivePuzzleId() != null && session.getActivePuzzleId().equals(pId)) {
                sb.append("谜题 ").append(pId).append(": 已激活，玩家正在解谜。\n");
            } else {
                sb.append("谜题 ").append(pId).append(": 未激活。如果玩家正在调查与谜题相关的事物，请使用 PUZZLE:ACTIVATE:").append(pId).append(" 激活谜题。\n");
            }
        }

        List<String> stillLocked = new ArrayList<>(mapNpcIds);
        stillLocked.removeAll(session.getUnlockedNpcs());

        // M1 修复：NPC暗示不再列出NPC名称和ID，仅告知数量和氛围暗示方式
        if (!stillLocked.isEmpty()) {
            sb.append("场景中存在 ").append(stillLocked.size()).append(" 个尚未现身的NPC（仅作为你环境描写的隐晦暗示参考，禁止在描写中直接提及NPC名称、外观或具体行为）。\n");
            sb.append("暗示方式：用氛围描写暗示「有人/什么东西在场」，如「暗处有什么东西在跟踪你」「阴影中有视线在窥视」「身后传来刻意压低的脚步声」「某个角落的空气似乎比别处更冷」。绝对不要写出NPC的名字、身份或具体动作。\n");
        }

        // M2 修复：未暴露物品同时列出ID和显示名称
        List<String> remainingItems = new ArrayList<>(mapItems);
        remainingItems.removeAll(session.getFoundItems());
        if (!remainingItems.isEmpty()) {
            sb.append("尚未暴露的物品: ");
            for (String id : remainingItems) {
                String displayName = resolveItemName(session, id);
                sb.append(id);
                if (!displayName.equals(id)) {
                    sb.append("(").append(displayName).append(")");
                }
                sb.append(" ");
            }
            sb.append("\n");
        }

        if (stillLocked.isEmpty() && remainingItems.isEmpty()) {
            appendSection(sb, prompts().getNoNewDiscoveryHint());
        }

        appendSection(sb, prompts().getAvailableTagsSection());

        String pacingRules = prompts().getExplorationPacingRules();
        if (pacingRules != null) {
            pacingRules = pacingRules
                    .replace("{turn}", String.valueOf(session.getTurn()))
                    .replace("{mapTurns}", String.valueOf(session.getMapTurns()));
            appendSection(sb, pacingRules);
        }

        if (gameConfig().getArchiveMapId() != null && gameConfig().getArchiveMapId().equals(currentMap.getId())) {
            appendSection(sb, prompts().getArchiveSpecialRule());
        }

        appendSection(sb, prompts().getKeyRules());
        appendSection(sb, prompts().getExampleResponse());

        return sb.toString();
    }

    private String resolveItemName(GameSession session, String itemId) {
        String dynamicName = session.getDynamicItemName(itemId);
        if (dynamicName != null) return dynamicName;
        ItemConfig item = dataLoader.getItem(itemId);
        return item != null ? nullToEmpty(item.getName()) : itemId;
    }

    /**
     * 检测玩家是否刚从谜题退出，若是则注入过渡提示，
     * 让 MapAI 的环境描写与谜题结果自然衔接。
     *
     * <p>注意：chatHistory 中的内容已被 stripInternal 移除控制标签，
     * 因此不能通过检测 PUZZLE:SOLVE/FAIL 文本来判断结果。
     * 改用 session 的 solvedPuzzles/failedPuzzles 集合来判断。</p>
     */
    private String appendPuzzleTransitionHint(GameSession session, String prompt) {
        List<GameSession.ChatMessage> history = session.getChatHistory();
        if (history == null || history.size() < 2) return prompt;

        // 检查最近几条消息中是否有谜题 AI 的输出（说明刚从谜题退出）
        int lookback = Math.min(4, history.size());
        String recentPuzzleId = null;

        for (int i = history.size() - 1; i >= history.size() - lookback; i--) {
            GameSession.ChatMessage msg = history.get(i);
            if ("PUZZLE_AI".equals(msg.senderType())) {
                // 找到最近的谜题AI消息，从消息内容推断谜题ID
                // 由于标签已被移除，通过 session 的 solved/failed 集合反查
                recentPuzzleId = findRecentPuzzleId(session);
                break;
            }
        }

        if (recentPuzzleId != null) {
            PuzzleConfig puzzle = dataLoader.getPuzzle(recentPuzzleId);
            String puzzleName = puzzle != null ? nullToEmpty(puzzle.getName()) : recentPuzzleId;
            boolean puzzleSolved = session.isPuzzleSolved(recentPuzzleId);
            String hint = puzzleSolved
                    ? "\n\n=== 谜题过渡提示 ===\n玩家刚刚成功解开了谜题「" + puzzleName + "」，从解谜状态回到探索。请在环境描写中自然地反映这一变化：解谜后的场景变化、新开启的通道或出口、氛围的微妙转变等。不要重复谜题 AI 已经描述过的内容，而是从环境视角补充描写。\n"
                    : "\n\n=== 谜题过渡提示 ===\n玩家刚刚在谜题「" + puzzleName + "」中遭遇了失败，从解谜状态回到探索。请在环境描写中体现失败后的氛围：更加压抑的环境、更紧迫的危机感等。不要重复谜题 AI 已经描述过的内容，而是从环境视角补充描写。\n";
            return prompt + hint;
        }

        return prompt;
    }

    /**
     * 从 session 的 solved/failed 集合中找到最近处理的谜题ID。
     * 优先返回 solvedPuzzles 中最后一个，其次 failedPuzzles 中最后一个。
     */
    private String findRecentPuzzleId(GameSession session) {
        // Set 无序，取最后一个元素不可靠；改为遍历当前地图的谜题列表，
        // 找到第一个已解决或已失败的谜题
        MapConfig currentMap = dataLoader.getMap(session.getCurrentMapId());
        if (currentMap != null && currentMap.getPuzzles() != null) {
            for (String pId : currentMap.getPuzzles()) {
                if (session.isPuzzleSolved(pId) || session.getFailedPuzzles().contains(pId)) {
                    return pId;
                }
            }
        }
        // 回退：从 Set 中任取一个
        Set<String> solved = session.getSolvedPuzzles();
        if (solved != null && !solved.isEmpty()) {
            return solved.iterator().next();
        }
        Set<String> failed = session.getFailedPuzzles();
        if (failed != null && !failed.isEmpty()) {
            return failed.iterator().next();
        }
        return null;
    }
}
