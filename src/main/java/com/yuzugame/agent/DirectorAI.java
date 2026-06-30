package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class DirectorAI extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(DirectorAI.class);

    private final GameDataLoader dataLoader;

    public DirectorAI(LlmService llm, GameDataLoader dataLoader) {
        super(llm);
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.DirectorPrompts prompts() {
        return dataLoader.getPrompts().getDirector();
    }

    public String openingMonologue(GameSession session, StoryConfig story, MapConfig startMap) {
        if (startMap == null || story == null) return "";
        String template = safeReplace(prompts().getOpeningMonologueTemplate(),
                "{mapName}", nullToEmpty(startMap.getName()));
        String prompt = nullToEmpty(story.getDirectorSystemPrompt()) + "\n\n=== 起始场景 ===\n"
                + "地图: " + nullToEmpty(startMap.getName()) + " (" + nullToEmpty(startMap.getId()) + ")\n"
                + "章节: " + nullToEmpty(startMap.getChapterName()) + "\n"
                + "场景描述: " + nullToEmpty(startMap.getDescription()) + "\n"
                + "氛围: " + nullToEmpty(startMap.getAtmosphere()) + "\n\n"
                + template;
        return chatWithSession(session, prompt, "[游戏启动，生成开场旁白]");
    }

    public String stageReport(GameSession session, MapConfig currentMap, StoryConfig story) {
        if (currentMap == null || story == null) return "";
        String prompt = buildPrompt(session, currentMap, story);

        String template = safeReplace(prompts().getStageReportTemplate(),
                "{turn}", String.valueOf(session.getTurn()),
                "{mapName}", nullToEmpty(currentMap.getName()),
                "{chapter}", nullToEmpty(session.getCurrentChapter()),
                "{solvedPuzzles}", String.valueOf(session.getSolvedPuzzles()),
                "{activePuzzle}", String.valueOf(session.getActivePuzzleId()),
                "{sanity}", String.valueOf(session.getPlayer().getSanity()),
                "{revelation}", String.valueOf(session.getPlayer().getRevelation()),
                "{affection}", String.valueOf(session.getPlayer().getAffection()));

        return chatWithSession(session, prompt, template);
    }

    public String ending(GameSession session, String endingType, StoryConfig story) {
        if (session == null || story == null) return "";
        Player player = session.getPlayer();
        String narrativeHint = resolveNarrativeHint(endingType);
        String prompt = nullToEmpty(story.getDirectorSystemPrompt()) + "\n\n触发结局类型：" + nullToEmpty(endingType) + "。" + nullToEmpty(narrativeHint);

        String contextTemplate = safeReplace(prompts().getEndingContextTemplate(),
                "{sanity}", String.valueOf(player.getSanity()),
                "{revelation}", String.valueOf(player.getRevelation()),
                "{affection}", String.valueOf(player.getAffection()),
                "{endingType}", nullToEmpty(endingType));

        StringBuilder context = new StringBuilder(contextTemplate);

        String transcript = formatChatHistory(session);
        if (!transcript.isEmpty()) {
            context.append("\n=== 玩家游玩记录 ===\n");
            context.append(transcript);
            context.append("\n请根据以上游玩记录和玩家属性，生成贴合玩家经历的个性化结局叙事。");
        }

        return chatWithSession(session, prompt, context.toString());
    }

    /**
     * 解析结局叙事提示 —— 三级回退：规则配置 → 默认提示映射 → 通用兜底。
     *
     * <p>D2 修复：对未知 endingType 不再硬回退到 FAIL 提示，
     * 而是使用通用的"结局已触发"兜底文案。</p>
     */
    private String resolveNarrativeHint(String endingType) {
        List<EndingRuleConfig> rules = dataLoader.getEndingRules();
        if (rules != null) {
            for (EndingRuleConfig rule : rules) {
                if (rule.getType() != null && rule.getType().equals(endingType) && rule.getNarrativeHint() != null) {
                    return rule.getNarrativeHint();
                }
            }
        }
        Map<String, String> hints = prompts().getEndingDefaultHints();
        if (hints != null && hints.containsKey(endingType)) {
            return hints.get(endingType);
        }
        // D2 修复：未知结局类型使用通用兜底，而非 FAIL 提示
        String genericFallback = "结局已触发，请生成对应的结局叙事。";
        if (hints != null && hints.containsKey("FAIL") && "FAIL".equals(endingType)) {
            return hints.get("FAIL");
        }
        String failHint = prompts().getEndingDefaultFailHint();
        return "FAIL".equals(endingType) && failHint != null ? failHint : genericFallback;
    }

    public String sanityWarning(GameSession session, MapConfig currentMap, StoryConfig story) {
        if (currentMap == null || story == null) return "";
        String prompt = buildPrompt(session, currentMap, story);
        String context = safeReplace(prompts().getSanityWarningTemplate(),
                "{sanity}", String.valueOf(session.getPlayer().getSanity()));
        return chatWithSession(session, prompt, context);
    }

    private String buildPrompt(GameSession session, MapConfig currentMap, StoryConfig story) {
        StringBuilder puzzleStatus = new StringBuilder();
        List<String> puzzles = currentMap.getPuzzles() != null ? currentMap.getPuzzles() : Collections.emptyList();
        for (String pId : puzzles) {
            if (session.isPuzzleSolved(pId)) puzzleStatus.append(pId).append("(已解) ");
            else if (session.getFailedPuzzles().contains(pId)) puzzleStatus.append(pId).append("(失败) ");
            else puzzleStatus.append(pId).append("(未解) ");
        }
        boolean anyPuzzleSolved = puzzles.stream().anyMatch(session::isPuzzleSolved);

        String template = safeReplace(prompts().getBuildPromptStateTemplate(),
                "{turn}", String.valueOf(session.getTurn()),
                "{mapName}", nullToEmpty(currentMap.getName()),
                "{mapId}", nullToEmpty(currentMap.getId()),
                "{chapter}", nullToEmpty(session.getCurrentChapter()),
                "{chapterName}", nullToEmpty(currentMap.getChapterName()),
                "{atmosphere}", nullToEmpty(currentMap.getAtmosphere()),
                "{sanity}", String.valueOf(session.getPlayer().getSanity()),
                "{revelation}", String.valueOf(session.getPlayer().getRevelation()),
                "{affection}", String.valueOf(session.getPlayer().getAffection()),
                "{puzzleStatus}", puzzleStatus.toString().trim(),
                "{nextMapId}", currentMap.getNextMapId() != null ? currentMap.getNextMapId() : "无",
                "{exitHint}", anyPuzzleSolved ? nullToEmpty(currentMap.getExitHint()) : "谜题未解，出口未显现");

        return nullToEmpty(story.getDirectorSystemPrompt()) + "\n\n=== 当前游戏状态 ===\n" + template;
    }

    /**
     * 结局规则按配置优先级匹配，优先级越小越先判定。
     */
    public String determineEndingAction(GameSession session, StoryConfig story) {
        List<EndingRuleConfig> rules = dataLoader.getEndingRules();
        if (rules == null) return null;

        for (EndingRuleConfig rule : rules) {
            if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
                continue;
            }
            boolean conditionsMet = evaluateEndingConditions(session, rule);
            if (!conditionsMet) continue;

            log.debug("Ending triggered: {} ({})", rule.getType(), rule.getDescription());
            return rule.getType();
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
                if (sender != null && sender.contains("{npcId}") && msg.npcId() != null) {
                    sender = sender.replace("{npcId}", msg.npcId());
                }
            } else {
                sender = msg.senderType();
            }
            sb.append("[").append(nullToEmpty(sender)).append("] ").append(nullToEmpty(msg.content())).append("\n");
        }
        return sb.toString();
    }

    private boolean evaluateEndingConditions(GameSession session, EndingRuleConfig rule) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            return false;
        }

        boolean isAnd = "AND".equalsIgnoreCase(rule.getLogic());
        for (EndingRuleConfig.EndingCondition cond : rule.getConditions()) {
            boolean result = evaluateSingleCondition(session, cond);
            if (isAnd && !result) return false;
            if (!isAnd && result) return true;
        }
        return isAnd;
    }

    /**
     * D3 修复：增加 Boolean 类型字段支持（如 exitUnlocked），
     * 并在 resolveFieldValue 中映射更多字段。
     */
    private boolean evaluateSingleCondition(GameSession session, EndingRuleConfig.EndingCondition cond) {
        if (cond.getField() == null || cond.getOperator() == null || cond.getValue() == null) {
            return false;
        }

        Object fieldValue = resolveFieldValue(session, cond.getField());
        Object expectedValue = cond.getValue();

        if (fieldValue == null) return false;

        String op = cond.getOperator();

        if (fieldValue instanceof Number fieldNum && expectedValue instanceof Number expectedNum) {
            double actual = fieldNum.doubleValue();
            double expected = expectedNum.doubleValue();
            return switch (op) {
                case "eq"  -> actual == expected;
                case "neq" -> actual != expected;
                case "gt"  -> actual > expected;
                case "gte" -> actual >= expected;
                case "lt"  -> actual < expected;
                case "lte" -> actual <= expected;
                default    -> false;
            };
        }

        if (fieldValue instanceof String fieldStr) {
            String expectedStr = String.valueOf(expectedValue);
            return switch (op) {
                case "eq"  -> fieldStr.equals(expectedStr);
                case "neq" -> !fieldStr.equals(expectedStr);
                default    -> false;
            };
        }

        // D3 修复：支持 Boolean 类型字段
        if (fieldValue instanceof Boolean fieldBool) {
            boolean expected = Boolean.parseBoolean(String.valueOf(expectedValue));
            return switch (op) {
                case "eq"  -> fieldBool == expected;
                case "neq" -> fieldBool != expected;
                default    -> false;
            };
        }

        return false;
    }

    /**
     * D3 修复：增加 exitUnlocked、ended 等 Boolean 字段映射，
     * 以及 aliveNpcCount 数值字段。
     */
    private Object resolveFieldValue(GameSession session, String field) {
        return switch (field) {
            case "player.sanity"     -> session.getPlayer().getSanity();
            case "player.revelation" -> session.getPlayer().getRevelation();
            case "player.affection"  -> session.getPlayer().getAffection();
            case "currentChapter"    -> session.getCurrentChapter();
            case "turn"              -> session.getTurn();
            case "score"             -> session.getScore();
            case "exitUnlocked"      -> session.isExitUnlocked();
            case "ended"             -> session.isEnded();
            case "aliveNpcCount"     -> session.getAliveNpcCount();
            default                  -> null;
        };
    }
}
