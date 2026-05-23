package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DirectorAI {

    private static final Logger log = LoggerFactory.getLogger(DirectorAI.class);

    private final LlmService llm;
    private final GameDataLoader dataLoader;

    public DirectorAI(LlmService llm, GameDataLoader dataLoader) {
        this.llm = llm;
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.DirectorPrompts prompts() {
        return dataLoader.getPrompts().getDirector();
    }

    public String openingMonologue(StoryConfig story, MapConfig startMap) {
        String template = prompts().getOpeningMonologueTemplate()
                .replace("{mapName}", startMap.getName());
        String prompt = story.getDirectorSystemPrompt() + "\n\n=== 起始场景 ===\n"
                + "地图: " + startMap.getName() + " (" + startMap.getId() + ")\n"
                + "章节: " + startMap.getChapterName() + "\n"
                + "场景描述: " + startMap.getDescription() + "\n"
                + "氛围: " + startMap.getAtmosphere() + "\n\n"
                + template;
        return llm.chat(prompt, "[游戏启动，生成开场旁白]");
    }

    public String stageReport(GameSession session, MapConfig currentMap, StoryConfig story) {
        String prompt = buildPrompt(session, currentMap, story);

        String template = prompts().getStageReportTemplate()
                .replace("{turn}", String.valueOf(session.getTurn()))
                .replace("{mapName}", currentMap.getName())
                .replace("{chapter}", session.getCurrentChapter())
                .replace("{solvedPuzzles}", String.valueOf(session.getSolvedPuzzles()))
                .replace("{activePuzzle}", String.valueOf(session.getActivePuzzleId()))
                .replace("{sanity}", String.valueOf(session.getPlayer().getSanity()))
                .replace("{revelation}", String.valueOf(session.getPlayer().getRevelation()))
                .replace("{affection}", String.valueOf(session.getPlayer().getAffection()));

        return llm.chat(prompt, template);
    }

    public String ending(GameSession session, String endingType, StoryConfig story) {
        Player player = session.getPlayer();
        String narrativeHint = resolveNarrativeHint(endingType);
        String prompt = story.getDirectorSystemPrompt() + "\n\n触发结局类型：" + endingType + "。" + narrativeHint;

        String contextTemplate = prompts().getEndingContextTemplate()
                .replace("{sanity}", String.valueOf(player.getSanity()))
                .replace("{revelation}", String.valueOf(player.getRevelation()))
                .replace("{affection}", String.valueOf(player.getAffection()))
                .replace("{endingType}", endingType);

        StringBuilder context = new StringBuilder(contextTemplate);

        String transcript = formatChatHistory(session); // 附带完整游玩记录，生成个性化结局
        if (!transcript.isEmpty()) {
            context.append("\n=== 玩家游玩记录 ===\n");
            context.append(transcript);
            context.append("\n请根据以上游玩记录和玩家属性，生成贴合玩家经历的个性化结局叙事。");
        }

        return llm.chat(prompt, context.toString());
    }

    private String resolveNarrativeHint(String endingType) {
        for (EndingRuleConfig rule : dataLoader.getEndingRules()) { // 优先从endings.json查找叙事提示
            if (rule.getType().equals(endingType) && rule.getNarrativeHint() != null) {
                return rule.getNarrativeHint();
            }
        }
        Map<String, String> hints = prompts().getEndingDefaultHints(); // 回退到prompts.json默认提示
        if (hints != null && hints.containsKey(endingType)) {
            return hints.get(endingType);
        }
        return hints != null ? hints.getOrDefault("FAIL", prompts().getEndingDefaultFailHint()) : prompts().getEndingDefaultFailHint();
    }

    public String sanityWarning(GameSession session, MapConfig currentMap, StoryConfig story) {
        String prompt = buildPrompt(session, currentMap, story);
        String context = prompts().getSanityWarningTemplate()
                .replace("{sanity}", String.valueOf(session.getPlayer().getSanity()));
        return llm.chat(prompt, context); // 仅叙事反馈，不改变状态（衰减由GameEngine处理）
    }

    private String buildPrompt(GameSession session, MapConfig currentMap, StoryConfig story) {
        StringBuilder puzzleStatus = new StringBuilder();
        List<String> puzzles = currentMap.getPuzzles();
        if (puzzles != null) {
            for (String pId : puzzles) {
                if (session.isPuzzleSolved(pId)) puzzleStatus.append(pId).append("(已解) ");
                else if (session.getFailedPuzzles().contains(pId)) puzzleStatus.append(pId).append("(失败) ");
                else puzzleStatus.append(pId).append("(未解) ");
            }
        }
        boolean anyPuzzleSolved = puzzles != null && puzzles.stream().anyMatch(session::isPuzzleSolved);

        String template = prompts().getBuildPromptStateTemplate()
                .replace("{turn}", String.valueOf(session.getTurn()))
                .replace("{mapName}", currentMap.getName())
                .replace("{mapId}", currentMap.getId())
                .replace("{chapter}", session.getCurrentChapter())
                .replace("{chapterName}", currentMap.getChapterName())
                .replace("{atmosphere}", currentMap.getAtmosphere())
                .replace("{sanity}", String.valueOf(session.getPlayer().getSanity()))
                .replace("{revelation}", String.valueOf(session.getPlayer().getRevelation()))
                .replace("{affection}", String.valueOf(session.getPlayer().getAffection()))
                .replace("{puzzleStatus}", puzzleStatus.toString().trim())
                .replace("{nextMapId}", currentMap.getNextMapId() != null ? currentMap.getNextMapId() : "无")
                .replace("{exitHint}", anyPuzzleSolved ? currentMap.getExitHint() : "谜题未解，出口未显现");

        return story.getDirectorSystemPrompt() + "\n\n=== 当前游戏状态 ===\n" + template;
    }

    public String determineEndingAction(GameSession session, StoryConfig story) {
        for (EndingRuleConfig rule : dataLoader.getEndingRules()) { // 按priority顺序逐条检查
            boolean conditionsMet = evaluateEndingConditions(session, rule);
            if (!conditionsMet) continue;

            log.debug("Ending triggered: {} ({})", rule.getType(), rule.getDescription());
            return rule.getType(); // 首次匹配即返回
        }
        return null;
    }

    String formatChatHistory(GameSession session) {
        Map<String, String> names = prompts().getChatHistorySenderNames();
        StringBuilder sb = new StringBuilder();
        for (GameSession.ChatMessage msg : session.getChatHistory()) {
            String sender;
            if (names != null && names.containsKey(msg.senderType())) {
                sender = names.get(msg.senderType());
                if (sender.contains("{npcId}") && msg.npcId() != null) { // NPC消息替换模板占位符
                    sender = sender.replace("{npcId}", msg.npcId());
                }
            } else {
                sender = msg.senderType();
            }
            sb.append("[").append(sender).append("] ").append(msg.content()).append("\n");
        }
        return sb.toString();
    }

    private boolean evaluateEndingConditions(GameSession session, EndingRuleConfig rule) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            return false;
        }

        boolean isAnd = "AND".equalsIgnoreCase(rule.getLogic()); // AND: 全部满足; OR: 任一满足
        for (EndingRuleConfig.EndingCondition cond : rule.getConditions()) {
            boolean result = evaluateSingleCondition(session, cond);
            if (isAnd && !result) return false; // AND短路：一个不满足即失败
            if (!isAnd && result) return true;  // OR短路：一个满足即通过
        }
        return isAnd;
    }

    private boolean evaluateSingleCondition(GameSession session, EndingRuleConfig.EndingCondition cond) {
        Object fieldValue = resolveFieldValue(session, cond.getField());
        Object expectedValue = cond.getValue();

        if (fieldValue == null) return false;

        if (fieldValue instanceof Number fieldNum) { // 数值比较：eq/neq/gt/gte/lt/lte
            double expectedNum = ((Number) expectedValue).doubleValue();
            double actualNum = fieldNum.doubleValue();
            return switch (cond.getOperator()) {
                case "eq"  -> actualNum == expectedNum;
                case "neq" -> actualNum != expectedNum;
                case "gt"  -> actualNum > expectedNum;
                case "gte" -> actualNum >= expectedNum;
                case "lt"  -> actualNum < expectedNum;
                case "lte" -> actualNum <= expectedNum;
                default    -> false;
            };
        }

        if (fieldValue instanceof String fieldStr) { // 字符串比较：仅支持eq/neq
            String expectedStr = String.valueOf(expectedValue);
            return switch (cond.getOperator()) {
                case "eq"  -> fieldStr.equals(expectedStr);
                case "neq" -> !fieldStr.equals(expectedStr);
                default    -> false;
            };
        }

        return false;
    }

    private Object resolveFieldValue(GameSession session, String field) { // 字段路径 → 值映射
        return switch (field) {
            case "player.sanity"     -> session.getPlayer().getSanity();
            case "player.revelation" -> session.getPlayer().getRevelation();
            case "player.affection"  -> session.getPlayer().getAffection();
            case "currentChapter"    -> session.getCurrentChapter();
            case "turn"              -> session.getTurn();
            case "score"             -> session.getScore();
            default                  -> null;
        };
    }
}
