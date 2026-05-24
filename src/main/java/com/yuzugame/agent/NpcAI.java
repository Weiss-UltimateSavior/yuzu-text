package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        String prompt = buildPrompt(session, npc, currentMap);
        List<Map<String, String>> history = buildMixedHistory(session, npc);
        return chatWithSession(session, prompt, playerMessage, history.isEmpty() ? null : history);
    }

    private List<Map<String, String>> buildMixedHistory(GameSession session, NpcConfig npc) {
        List<GameSession.ChatMessage> allMessages = session.getChatHistory();
        int maxMessages = gameConfig().getNpcAiHistoryLimit(); // NPC AI仅保留最近N条
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
                case "NPC_AI" -> {
                    if (npc.getId().equals(msg.npcId())) { // 自己的对话作为assistant
                        role = "assistant";
                        content = msg.content();
                    } else { // 其他NPC的对话作为上下文
                        role = "user";
                        String label = labels != null ? labels.getOrDefault("NPC_AI_OTHER", "{npcId}") : "{npcId}";
                        content = "【" + label.replace("{npcId}", msg.npcId() != null ? msg.npcId() : "未知") + "】" + msg.content();
                    }
                }
                case "PROTAGONIST_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("PROTAGONIST_AI", "柚子") : "柚子";
                    content = "【" + label + "】" + msg.content();
                }
                case "MAP_AI" -> {
                    role = "user";
                    String label = labels != null ? labels.getOrDefault("MAP_AI", "环境") : "环境";
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

    private String buildPrompt(GameSession session, NpcConfig npc, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder();
        sb.append(prompts().getRoleIntroTemplate()
                .replace("{npcName}", npc.getName())).append("\n\n");

        sb.append("## 角色设定\n");
        sb.append("身份: ").append(npc.getDescription()).append("\n");
        sb.append("性格: ").append(npc.getPersonality()).append("\n");
        sb.append("背景: ").append(npc.getBackground()).append("\n");
        sb.append("说话风格: ").append(npc.getDialogueStyle()).append("\n");

        sb.append("\n## 你知晓的信息\n");
        if (npc.getKnownInfo() != null) {
            for (String info : npc.getKnownInfo()) {
                sb.append("- ").append(info).append("\n");
            }
        }

        sb.append("\n## 当前状态\n");
        sb.append("所在地图: ").append(currentMap.getName()).append(" - ").append(currentMap.getChapterName()).append("\n");
        sb.append("与玩家对话次数: ").append(session.getNpcDialogueCount(npc.getId())).append("\n");
        sb.append("游戏回合: ").append(session.getTurn()).append("/100\n");

        sb.append("\n## 谜题状态\n");
        for (String pId : currentMap.getPuzzles()) {
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

        sb.append("\n").append(prompts().getReplyRules()).append("\n");

        int dialogueCount = session.getNpcDialogueCount(npc.getId());
        int giftThreshold = gameConfig().getNpcGiftDialogueThreshold();
        String giftItemId = gameConfig().getNpcGiftItemIdTemplate().replace("{npcId}", npc.getId());
        boolean playerHasGift = session.getPlayer().hasItem(giftItemId);

        if (playerHasGift) {
            sb.append("\n").append(prompts().getAlreadyGiftedRule()).append("\n");
        } else if (dialogueCount >= giftThreshold - 1) {
            String giftRule = prompts().getThirdDialogueGiftRule()
                    .replace("{npcId}", npc.getId())
                    .replace("{itemIdTemplate}", gameConfig().getNpcGiftItemIdTemplate())
                    .replace("{nameTemplate}", gameConfig().getNpcGiftNameTemplate());
            sb.append("\n").append(giftRule).append("\n");
        }

        sb.append("\n").append(prompts().getCriticalRules()).append("\n");
        sb.append("\n").append(prompts().getExampleResponse()).append("\n");

        sb.append("\n").append(prompts().getChatContextExplanation()).append("\n");

        if (session.getActivePuzzleId() != null) { // 有活跃谜题时提示NPC可提供线索
            sb.append("\n").append(prompts().getActivePuzzleHintTemplate()
                    .replace("{puzzleId}", session.getActivePuzzleId())).append("\n");
        }

        return sb.toString();
    }
}
