package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ProtagonistAI {

    private final LlmService llm;
    private final GameDataLoader dataLoader;

    public ProtagonistAI(LlmService llm, GameDataLoader dataLoader) {
        this.llm = llm;
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.ProtagonistPrompts prompts() {
        return dataLoader.getPrompts().getProtagonist();
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    public String respond(GameSession session, ProtagonistConfig config, MapConfig currentMap, String playerMessage) {
        String prompt = buildPrompt(session, config, currentMap);
        List<Map<String, String>> history = buildMixedHistory(session);
        return llm.chat(prompt, playerMessage, history.isEmpty() ? null : history);
    }

    public String opening(GameSession session, ProtagonistConfig config, MapConfig currentMap) {
        String prompt = buildPrompt(session, config, currentMap);
        prompt += "\n\n" + prompts().getOpeningPrompt();
        return llm.chat(prompt, prompts().getOpeningUserMessage());
    }

    public String endingLine(Player player, ProtagonistConfig config, String endingType) {
        String context = prompts().getEndingLineTemplate()
                .replace("{endingType}", endingType)
                .replace("{sanity}", String.valueOf(player.getSanity()))
                .replace("{revelation}", String.valueOf(player.getRevelation()))
                .replace("{affection}", String.valueOf(player.getAffection()));
        return llm.chat(config.getSystemPrompt(), context); // 结局时柚子的最后台词
    }

    private List<Map<String, String>> buildMixedHistory(GameSession session) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        int maxMessages = gameConfig().getProtagonistAiHistoryLimit(); // 柚子AI保留最近N条（最多）
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
                case "PROTAGONIST_AI" -> {
                    role = "assistant"; // 自己的输出作为assistant
                    content = msg.content();
                }
                case "MAP_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("MAP_AI", "环境") : "环境";
                    content = "【" + label + "】" + msg.content();
                }
                case "NPC_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("NPC_AI", "{npcId}") : "{npcId}";
                    content = "【" + label.replace("{npcId}", msg.npcId() != null ? msg.npcId() : "未知") + "】" + msg.content();
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

    private String buildPrompt(GameSession session, ProtagonistConfig config, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder(config.getSystemPrompt());
        sb.append("\n\n").append(prompts().getStateHeader()).append("\n");

        String stateTemplate = prompts().getStateTemplate()
                .replace("{turn}", String.valueOf(session.getTurn()))
                .replace("{mapName}", currentMap != null ? currentMap.getName() : "未知")
                .replace("{mapDesc}", currentMap != null ? currentMap.getDescription() : "")
                .replace("{chapter}", currentMap != null ? currentMap.getChapterName() : "未知")
                .replace("{affection}", String.valueOf(session.getPlayer().getAffection()))
                .replace("{sanity}", String.valueOf(session.getPlayer().getSanity()))
                .replace("{revelation}", String.valueOf(session.getPlayer().getRevelation()))
                .replace("{gamePhase}", session.getGamePhase())
                .replace("{activePuzzle}", session.getActivePuzzleId() != null ? session.getActivePuzzleId() : "无");
        sb.append(stateTemplate).append("\n");

        if (!session.getPlayer().getInventory().isEmpty()) { // 背包物品
            sb.append(prompts().getInventoryLabel()).append(" ");
            for (String itemId : session.getPlayer().getInventory()) {
                sb.append(itemId).append(" ");
            }
            sb.append("\n");
        }

        if (!session.getYuzuInventory().isEmpty()) { // 柚子持有的物品
            sb.append("你持有的物品: ");
            for (String itemId : session.getYuzuInventory()) {
                sb.append(itemId).append(" ");
            }
            sb.append("\n");
        }

        sb.append(prompts().getFoundItemsLabel()).append(" ");
        for (String itemId : session.getFoundItems()) {
            if (!session.getPlayer().hasItem(itemId)) { // 已发现但未拾取的物品
                sb.append(itemId).append("(").append(prompts().getNotPickedUpLabel()).append(") ");
            }
        }

        sb.append("\n\n").append(prompts().getCtrlTagExample()).append("\n");
        sb.append("\n").append(prompts().getChatContextExplanation()).append("\n");

        if (session.getTurn() > 1) { // 非首回合：不再重复自我介绍
            sb.append("\n").append(prompts().getNoReIntroReminder()).append("\n");
        }

        if (session.getActivePuzzleId() != null) { // 有活跃谜题时：简短回复，不抢谜题风头
            sb.append("\n\n").append(prompts().getActivePuzzleShortReplyRule()).append("\n");
        }

        return sb.toString();
    }
}
