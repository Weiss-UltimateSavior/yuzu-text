package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ProtagonistAI extends BaseAgent {

    private final GameDataLoader dataLoader;

    public ProtagonistAI(LlmService llm, GameDataLoader dataLoader) {
        super(llm);
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.ProtagonistPrompts prompts() {
        return dataLoader.getPrompts().getProtagonist();
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    public String respond(GameSession session, ProtagonistConfig config, MapConfig currentMap, String playerMessage) {
        if (session == null) return "";
        String prompt = buildPrompt(session, config, currentMap);
        List<Map<String, String>> history = buildMixedHistory(session);
        return chatWithSession(session, prompt, nullToEmpty(playerMessage), history.isEmpty() ? null : history);
    }

    public String opening(GameSession session, ProtagonistConfig config, MapConfig currentMap) {
        if (session == null) return "";
        String prompt = buildPrompt(session, config, currentMap);
        String openingMsg = nullToEmpty(prompts().getOpeningUserMessage());
        return chatWithSession(session, prompt, openingMsg);
    }

    /**
     * P2 修复：结局台词注入游戏状态上下文，使台词更贴合玩家经历。
     */
    public String endingLine(GameSession session, Player player, ProtagonistConfig config, String endingType) {
        if (session == null) return "";
        StringBuilder prompt = new StringBuilder(nullToEmpty(config.getSystemPrompt()));
        prompt.append("\n\n=== 结局上下文 ===\n");
        prompt.append("结局类型: ").append(nullToEmpty(endingType)).append("\n");
        prompt.append("最终理智: ").append(player.getSanity()).append("\n");
        prompt.append("最终启示: ").append(player.getRevelation()).append("\n");
        prompt.append("最终好感: ").append(player.getAffection()).append("\n");
        prompt.append("游戏回合: ").append(session.getTurn()).append("\n");
        prompt.append("已解谜题: ").append(session.getSolvedPuzzles()).append("\n");
        prompt.append("最终章节: ").append(nullToEmpty(session.getCurrentChapter())).append("\n");

        String template = safeReplace(prompts().getEndingLineTemplate(),
                "{endingType}", nullToEmpty(endingType));
        return chatWithSession(session, prompt.toString(), template);
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
                case "NPC_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("NPC_AI", "{npcId}") : "{npcId}";
                    content = "【" + label.replace("{npcId}", msg.npcId() != null ? msg.npcId() : "未知") + "】" + nullToEmpty(msg.content());
                }
                case "MAP_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("MAP_AI", "环境") : "环境";
                    content = "【" + label + "】" + nullToEmpty(msg.content());
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

        sb.append("\n\n=== 当前状态 ===\n");
        sb.append("回合: ").append(session.getTurn()).append("\n");
        if (currentMap != null) {
            sb.append("地图: ").append(nullToEmpty(currentMap.getName())).append("\n");
            sb.append("章节: ").append(nullToEmpty(currentMap.getChapterName())).append("\n");
        }
        sb.append("理智: ").append(session.getPlayer().getSanity()).append("/100\n");
        sb.append("启示: ").append(session.getPlayer().getRevelation()).append("/100\n");
        sb.append("好感: ").append(session.getPlayer().getAffection()).append("/100\n");

        // P3 修复：物品列表同时显示ID和名称
        if (!session.getPlayer().getInventory().isEmpty()) {
            sb.append(nullToEmpty(prompts().getInventoryLabel())).append(" ");
            for (String id : session.getPlayer().getInventory()) {
                sb.append(formatItem(session, id)).append(" ");
            }
            sb.append("\n");
        }

        if (!session.getYuzuInventory().isEmpty()) {
            sb.append("柚子持有物品: ");
            for (String id : session.getYuzuInventory()) {
                sb.append(formatItem(session, id)).append(" ");
            }
            sb.append("\n");
        }

        // P1 修复：已发现物品排除柚子已持有的物品
        List<String> foundButNotHeld = new ArrayList<>(session.getFoundItems());
        foundButNotHeld.removeAll(session.getPlayer().getInventory());
        foundButNotHeld.removeAll(session.getYuzuInventory());
        if (!foundButNotHeld.isEmpty()) {
            sb.append(nullToEmpty(prompts().getNotPickedUpLabel())).append(" ");
            for (String id : foundButNotHeld) {
                sb.append(formatItem(session, id)).append(" ");
            }
            sb.append("\n");
        }

        if (session.getActivePuzzleId() != null) {
            appendSection(sb, prompts().getActivePuzzleShortReplyRule());
        }

        if (session.getTurn() > 1) {
            appendSection(sb, prompts().getNoReIntroReminder());
        }

        appendSection(sb, prompts().getCtrlTagExample());
        appendSection(sb, prompts().getChatContextExplanation());

        return sb.toString();
    }

    /**
     * P3 修复：格式化物品显示，同时包含ID和显示名称。
     */
    private String formatItem(GameSession session, String itemId) {
        String displayName = resolveItemName(session, itemId);
        if (!displayName.equals(itemId)) {
            return itemId + "(" + displayName + ")";
        }
        return itemId;
    }

    private String resolveItemName(GameSession session, String itemId) {
        String dynamicName = session.getDynamicItemName(itemId);
        if (dynamicName != null) return dynamicName;
        ItemConfig item = dataLoader.getItem(itemId);
        return item != null ? nullToEmpty(item.getName()) : itemId;
    }
}
