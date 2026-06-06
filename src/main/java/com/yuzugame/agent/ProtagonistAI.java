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

    private String chatWithSession(GameSession session, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        if (session.hasCustomLlm()) {
            return llm.chat(systemPrompt, userMessage, history, session.getCustomLlmBaseUrl(), session.getCustomLlmApiKey(), session.getCustomLlmModel());
        }
        return llm.chat(systemPrompt, userMessage, history);
    }

    private String chatWithSession(GameSession session, String systemPrompt, String userMessage) {
        return chatWithSession(session, systemPrompt, userMessage, null);
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    public String respond(GameSession session, ProtagonistConfig config, MapConfig currentMap, String playerMessage) {
        if (config == null) return "";
        String prompt = buildPrompt(session, config, currentMap);
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, nullToEmpty(playerMessage), history.isEmpty() ? null : history);
    }

    public String opening(GameSession session, ProtagonistConfig config, MapConfig currentMap) {
        if (config == null) return "";
        String prompt = buildPrompt(session, config, currentMap);
        String openingPrompt = prompts().getOpeningPrompt();
        if (openingPrompt != null) {
            prompt += "\n\n" + openingPrompt;
        }
        return chatWithSession(session, prompt, nullToEmpty(prompts().getOpeningUserMessage()));
    }

    public String endingLine(GameSession session, Player player, ProtagonistConfig config, String endingType) {
        if (config == null || player == null) return "";
        String context = safeReplace(prompts().getEndingLineTemplate(),
                "{endingType}", nullToEmpty(endingType),
                "{sanity}", String.valueOf(player.getSanity()),
                "{revelation}", String.valueOf(player.getRevelation()),
                "{affection}", String.valueOf(player.getAffection()));
        return chatWithSession(session, nullToEmpty(config.getSystemPrompt()), context);
    }

    private List<Map<String, String>> buildMixedHistory(GameSession session) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        int maxMessages = gameConfig().getProtagonistAiHistoryLimit();
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
                case "PROTAGONIST_AI" -> {
                    role = "assistant";
                    content = msg.content();
                }
                case "MAP_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("MAP_AI", "环境") : "环境";
                    content = "【" + label + "】" + nullToEmpty(msg.content());
                }
                case "NPC_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("NPC_AI", "{npcId}") : "{npcId}";
                    content = "【" + label.replace("{npcId}", msg.npcId() != null ? msg.npcId() : "未知") + "】" + nullToEmpty(msg.content());
                }
                case "DIRECTOR_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("DIRECTOR_AI", "导演旁白") : "导演旁白";
                    content = "【" + label + "】" + nullToEmpty(msg.content());
                }
                case "PUZZLE_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("PUZZLE_AI", "谜题") : "谜题";
                    content = "【" + label + "】" + nullToEmpty(msg.content());
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

    private String buildPrompt(GameSession session, ProtagonistConfig config, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder();
        appendNonNull(sb, config.getSystemPrompt());
        appendSection(sb, prompts().getStateHeader());

        String stateTemplate = safeReplace(prompts().getStateTemplate(),
                "{turn}", String.valueOf(session.getTurn()),
                "{mapName}", currentMap != null ? nullToEmpty(currentMap.getName()) : "未知",
                "{mapDesc}", currentMap != null ? nullToEmpty(currentMap.getDescription()) : "",
                "{chapter}", currentMap != null ? nullToEmpty(currentMap.getChapterName()) : "未知",
                "{affection}", String.valueOf(session.getPlayer().getAffection()),
                "{sanity}", String.valueOf(session.getPlayer().getSanity()),
                "{revelation}", String.valueOf(session.getPlayer().getRevelation()),
                "{gamePhase}", nullToEmpty(session.getGamePhase()),
                "{activePuzzle}", session.getActivePuzzleId() != null ? session.getActivePuzzleId() : "无");
        sb.append(stateTemplate).append("\n");

        if (!session.getPlayer().getInventory().isEmpty()) {
            sb.append(nullToEmpty(prompts().getInventoryLabel())).append(" ");
            for (String itemId : session.getPlayer().getInventory()) {
                sb.append(itemId).append(" ");
            }
            sb.append("\n");
        }

        if (!session.getYuzuInventory().isEmpty()) {
            sb.append("你持有的物品: ");
            for (String itemId : session.getYuzuInventory()) {
                sb.append(itemId).append(" ");
            }
            sb.append("\n");
        }

        sb.append(nullToEmpty(prompts().getFoundItemsLabel())).append(" ");
        for (String itemId : session.getFoundItems()) {
            if (!session.getPlayer().hasItem(itemId)) {
                sb.append(itemId).append("(").append(nullToEmpty(prompts().getNotPickedUpLabel())).append(") ");
            }
        }

        appendSection(sb, prompts().getCtrlTagExample());
        appendSection(sb, prompts().getChatContextExplanation());

        if (session.getTurn() > 1) {
            appendSection(sb, prompts().getNoReIntroReminder());
        }

        if (session.getActivePuzzleId() != null) {
            appendSection(sb, prompts().getActivePuzzleShortReplyRule());
        }

        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String safeReplace(String template, String... pairs) {
        if (template == null) return "";
        String result = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace(pairs[i], pairs[i + 1] != null ? pairs[i + 1] : "");
        }
        return result;
    }

    private static void appendSection(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            sb.append("\n").append(text).append("\n");
        }
    }

    private static void appendNonNull(StringBuilder sb, String text) {
        if (text != null) {
            sb.append(text);
        }
    }
}
