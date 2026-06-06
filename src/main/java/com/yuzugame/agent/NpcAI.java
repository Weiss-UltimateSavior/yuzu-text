package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class NpcAI {

    private final LlmService llm;
    private final GameDataLoader dataLoader;

    public NpcAI(LlmService llm, GameDataLoader dataLoader) {
        this.llm = llm;
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.NpcPrompts prompts() {
        return dataLoader.getPrompts().getNpc();
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

    public String respond(GameSession session, NpcConfig npc, MapConfig currentMap, String playerMessage) {
        if (npc == null || currentMap == null) return "";
        String prompt = buildPrompt(session, npc, currentMap);
        List<Map<String, String>> history = buildMixedHistory(session, npc);
        return chatWithSession(session, prompt, nullToEmpty(playerMessage), history.isEmpty() ? null : history);
    }

    private List<Map<String, String>> buildMixedHistory(GameSession session, NpcConfig npc) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        int maxMessages = gameConfig().getNpcAiHistoryLimit();
        int start = Math.max(0, allMessages.size() - maxMessages);
        List<GameSession.ChatMessage> recent = new ArrayList<>(allMessages.subList(start, allMessages.size()));

        String npcId = npc.getId();
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
                case "NPC_AI" -> {
                    if (npcId != null && npcId.equals(msg.npcId())) {
                        role = "assistant";
                        content = msg.content();
                    } else {
                        role = "user";
                        String label = labels != null ? labels.getOrDefault("NPC_AI_OTHER", "{npcId}") : "{npcId}";
                        content = "【" + label.replace("{npcId}", msg.npcId() != null ? msg.npcId() : "未知") + "】" + nullToEmpty(msg.content());
                    }
                }
                case "PROTAGONIST_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("PROTAGONIST_AI", "柚子") : "柚子";
                    content = "【" + label + "】" + nullToEmpty(msg.content());
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

    private String buildPrompt(GameSession session, NpcConfig npc, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder();
        String roleIntro = safeReplace(prompts().getRoleIntroTemplate(),
                "{npcName}", nullToEmpty(npc.getName()));
        sb.append(roleIntro).append("\n\n");

        sb.append("## 角色设定\n");
        sb.append("身份: ").append(nullToEmpty(npc.getDescription())).append("\n");
        sb.append("性格: ").append(nullToEmpty(npc.getPersonality())).append("\n");
        sb.append("背景: ").append(nullToEmpty(npc.getBackground())).append("\n");
        sb.append("说话风格: ").append(nullToEmpty(npc.getDialogueStyle())).append("\n");

        sb.append("\n## 你知晓的信息\n");
        if (npc.getKnownInfo() != null) {
            for (String info : npc.getKnownInfo()) {
                sb.append("- ").append(nullToEmpty(info)).append("\n");
            }
        }

        sb.append("\n## 当前状态\n");
        sb.append("所在地图: ").append(nullToEmpty(currentMap.getName())).append(" - ").append(nullToEmpty(currentMap.getChapterName())).append("\n");
        sb.append("与玩家对话次数: ").append(session.getNpcDialogueCount(npc.getId())).append("\n");
        sb.append("游戏回合: ").append(session.getTurn()).append("/100\n");

        sb.append("\n## 谜题状态\n");
        List<String> puzzles = currentMap.getPuzzles() != null ? currentMap.getPuzzles() : Collections.emptyList();
        for (String pId : puzzles) {
            if (session.isPuzzleSolved(pId)) {
                sb.append("- ").append(pId).append(": 已解决\n");
            } else if (session.getFailedPuzzles().contains(pId)) {
                sb.append("- ").append(pId).append(": 已失败\n");
            } else if (session.getActivePuzzleId() != null && session.getActivePuzzleId().equals(pId)) {
                sb.append("- ").append(pId).append(": 正在解谜中\n");
            } else {
                sb.append("- ").append(pId).append(": 未激活\n");
            }
        }

        appendSection(sb, prompts().getReplyRules());

        int dialogueCount = session.getNpcDialogueCount(npc.getId());
        int giftThreshold = gameConfig().getNpcGiftDialogueThreshold();
        String giftItemIdTemplate = gameConfig().getNpcGiftItemIdTemplate();
        String giftItemId = giftItemIdTemplate != null ? giftItemIdTemplate.replace("{npcId}", nullToEmpty(npc.getId())) : "";
        boolean playerHasGift = !giftItemId.isEmpty() && session.getPlayer().hasItem(giftItemId);

        if (playerHasGift) {
            appendSection(sb, prompts().getAlreadyGiftedRule());
        } else if (dialogueCount >= giftThreshold - 1) {
            String giftRule = safeReplace(prompts().getThirdDialogueGiftRule(),
                    "{npcId}", nullToEmpty(npc.getId()),
                    "{itemIdTemplate}", nullToEmpty(giftItemIdTemplate),
                    "{nameTemplate}", nullToEmpty(gameConfig().getNpcGiftNameTemplate()));
            appendSection(sb, giftRule);
        }

        appendSection(sb, prompts().getCriticalRules());
        appendSection(sb, prompts().getExampleResponse());
        appendSection(sb, prompts().getChatContextExplanation());

        if (session.getActivePuzzleId() != null) {
            String puzzleHint = safeReplace(prompts().getActivePuzzleHintTemplate(),
                    "{puzzleId}", session.getActivePuzzleId());
            appendSection(sb, puzzleHint);
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
}
