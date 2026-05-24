package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MapAI {

    private final LlmService llm;
    private final GameDataLoader dataLoader;

    public MapAI(LlmService llm, GameDataLoader dataLoader) {
        this.llm = llm;
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.MapPrompts prompts() {
        return dataLoader.getPrompts().getMap();
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    private String chatWithSession(GameSession session, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        if (session.hasCustomLlm()) {
            return llm.chat(systemPrompt, userMessage, history, session.getCustomLlmBaseUrl(), session.getCustomLlmApiKey(), session.getCustomLlmModel());
        }
        return llm.chat(systemPrompt, userMessage, history);
    }

    public String describe(GameSession session, MapConfig currentMap) {
        String prompt = buildPrompt(session, currentMap);
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, "[玩家正在观察周围环境]", history.isEmpty() ? null : history);
    }

    public String autoDescribe(GameSession session, MapConfig currentMap) {
        String prompt = buildPrompt(session, currentMap);
        prompt += "\n\n" + prompts().getAutoDescribePrompt(); // 首次进入：强制环境描写
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, "[玩家首次进入 " + currentMap.getName() + "，提供环境描写]", history.isEmpty() ? null : history);
    }

    public String transitionDescribe(GameSession session, MapConfig fromMap, MapConfig toMap) {
        String prompt = buildPrompt(session, toMap);
        String template = prompts().getTransitionDescribeTemplate()
                .replace("{fromMapName}", fromMap.getName())
                .replace("{fromMapDesc}", fromMap.getDescription())
                .replace("{fromExitHint}", fromMap.getExitHint())
                .replace("{toMapName}", toMap.getName());
        prompt += "\n\n" + template;
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, "[玩家从" + fromMap.getName() + "前往" + toMap.getName() + "，描写过渡场景]", history.isEmpty() ? null : history);
    }

    private List<Map<String, String>> buildMixedHistory(GameSession session) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        int maxMessages = gameConfig().getMapAiHistoryLimit(); // 地图AI仅保留最近N条
        int start = Math.max(0, allMessages.size() - maxMessages);
        List<GameSession.ChatMessage> recent = allMessages.subList(start, allMessages.size());

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
                    role = "assistant"; // 自己的输出作为assistant
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

            if (!content.isBlank()) {
                history.add(Map.of("role", role, "content", content));
            }
        }

        return history;
    }

    private String buildPrompt(GameSession session, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder(currentMap.getSystemPrompt());

        if (currentMap.getAreas() != null && !currentMap.getAreas().isEmpty()) {
            sb.append("\n\n=== 区域划分 ===\n");
            int idx = 1;
            for (Map<String, String> area : currentMap.getAreas()) {
                sb.append(idx++).append(". ").append(area.get("name")).append("：").append(area.get("description")).append("\n");
            }
        }

        sb.append("\n\n=== 场景状态 ===\n");
        sb.append("地图: ").append(currentMap.getName()).append("\n");
        sb.append("章节: ").append(currentMap.getChapterName()).append("\n");
        sb.append("气氛: ").append(currentMap.getAtmosphere()).append("\n");
        sb.append("当前回合: ").append(session.getTurn()).append(" (本地图内: ").append(session.getMapTurns()).append(")\n");
        sb.append("玩家理智: ").append(session.getPlayer().getSanity()).append("/100\n");
        if (session.getCurrentArea() != null) {
            sb.append("玩家当前位置: ").append(session.getCurrentArea()).append("\n");
        }
        int foundInMap = (int) currentMap.getItems().stream().filter(session::isItemFound).count();
        int totalInMap = currentMap.getItems().size();
        sb.append("已发现物品: ").append(foundInMap).append("/").append(totalInMap).append("\n");
        sb.append("已解锁NPC数: ").append(currentMap.getNpcIds().stream().filter(session::isNpcUnlocked).count()).append("/").append(currentMap.getNpcIds().size()).append("\n");

        for (String pId : currentMap.getPuzzles()) {
            if (session.isPuzzleSolved(pId)) {
                sb.append("谜题 ").append(pId).append(": 已解决。出口:").append(currentMap.getExitHint()).append("\n");
            } else if (session.getFailedPuzzles().contains(pId)) {
                sb.append("谜题 ").append(pId).append(": 已失败（以代价通过）。\n");
            } else if (session.getActivePuzzleId() != null && session.getActivePuzzleId().equals(pId)) {
                sb.append("谜题 ").append(pId).append(": 已激活，玩家正在解谜。\n");
            } else {
                sb.append("谜题 ").append(pId).append(": 未激活。如果玩家正在调查与谜题相关的事物，请使用 PUZZLE:ACTIVATE:").append(pId).append(" 激活谜题。\n");
            }
        }

        List<String> stillLocked = new ArrayList<>(currentMap.getNpcIds());
        stillLocked.removeAll(session.getUnlockedNpcs());
        stillLocked.removeAll(session.getKilledNpcs());

        if (!stillLocked.isEmpty()) {
            sb.append("场景中存在尚未现身的NPC（仅作为你环境描写的隐晦暗示参考，禁止在描写中直接提及NPC名称、外观或具体行为）:\n");
            for (String id : stillLocked) {
                NpcConfig npcCfg = dataLoader.getNpc(id);
                if (npcCfg != null) {
                    sb.append("- ").append(id).append("(").append(npcCfg.getName()).append(")\n");
                } else {
                    sb.append("- ").append(id).append("\n");
                }
            }
            sb.append("暗示方式：用氛围描写暗示「有人/什么东西在场」，如「暗处有什么东西在跟踪你」「阴影中有视线在窥视」「身后传来刻意压低的脚步声」「某个角落的空气似乎比别处更冷」。绝对不要写出NPC的名字、身份或具体动作。\n");
        }

        List<String> remainingItems = new ArrayList<>(currentMap.getItems());
        remainingItems.removeAll(session.getFoundItems());
        if (!remainingItems.isEmpty()) { // 未暴露物品：等待探索时自然发现
            sb.append("尚未暴露的物品: ");
            for (String id : remainingItems) {
                sb.append(id).append(" ");
            }
            sb.append("\n");
        }

        if (stillLocked.isEmpty() && remainingItems.isEmpty()) { // 本地图已无新内容
            sb.append("\n").append(prompts().getNoNewDiscoveryHint()).append("\n");
        }

        sb.append("\n").append(prompts().getAvailableTagsSection()).append("\n");

        String pacingRules = prompts().getExplorationPacingRules()
                .replace("{turn}", String.valueOf(session.getTurn()))
                .replace("{mapTurns}", String.valueOf(session.getMapTurns()));
        sb.append("\n").append(pacingRules).append("\n");

        if (gameConfig().getArchiveMapId().equals(currentMap.getId())) { // 档案馆特殊规则：额外揭露度
            sb.append("\n").append(prompts().getArchiveSpecialRule()).append("\n");
        }

        sb.append("\n").append(prompts().getKeyRules()).append("\n");
        sb.append("\n").append(prompts().getExampleResponse());

        return sb.toString();
    }
}
