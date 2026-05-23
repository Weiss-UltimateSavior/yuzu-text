package com.yuzugame.engine;

import com.yuzugame.model.GameSession;
import org.springframework.stereotype.Component;

/**
 * 条件表达式求值器 —— 用于判断游戏中的条件是否满足。
 *
 * <p>支持的条件类型：
 * <ul>
 *   <li>{@code chapter:id} — 当前章节是否为指定章节</li>
 *   <li>{@code puzzle:id:success|failed} — 谜题是否已解决/失败</li>
 *   <li>{@code item:id} — 玩家是否持有指定物品</li>
 *   <li>{@code revelation:N} — 揭露度是否≥N</li>
 *   <li>{@code turn:N} — 回合数是否≥N</li>
 *   <li>{@code npcDialogue:npcId:N} — 与指定NPC的对话次数是否≥N</li>
 *   <li>{@code affection:N} — 柚子好感度是否≥N</li>
 * </ul></p>
 *
 * <p>支持逻辑组合：
 * <ul>
 *   <li>{@code |}（OR）—— 条件之间用 | 分隔，任一满足即返回 true</li>
 *   <li>{@code &}（AND）—— 条件之间用 & 分隔，全部满足才返回 true</li>
 * </ul>
 * 优先级：AND > OR（先拆分 OR，再对每段拆分 AND）</p>
 */
@Component
public class ConditionEvaluator {

    public boolean evaluate(GameSession session, String condition) {
        if (condition == null || condition.isBlank()) {
            return true;
        }

        String[] parts = condition.split(":", 3);
        if (parts.length < 2) return false;

        String type = parts[0];
        String value = parts.length > 1 ? parts[1] : "";
        String param = parts.length > 2 ? parts[2] : "";

        return switch (type) {
            case "chapter" -> evaluateChapter(session, value);
            case "puzzle" -> evaluatePuzzle(session, value, param);
            case "item" -> evaluateItem(session, value);
            case "revelation" -> evaluateRevelation(session, value);
            case "turn" -> evaluateTurn(session, value);
            case "npcDialogue" -> evaluateNpcDialogueCount(session, value, param);
            case "affection" -> evaluateAffection(session, value);
            default -> false;
        };
    }

    private boolean evaluateChapter(GameSession session, String chapterId) {
        return chapterId.equals(session.getCurrentChapter());
    }

    private boolean evaluatePuzzle(GameSession session, String puzzleId, String status) {
        if ("success".equals(status)) {
            return session.isPuzzleSolved(puzzleId);
        }
        if ("failed".equals(status)) {
            return session.getFailedPuzzles().contains(puzzleId);
        }
        return false;
    }

    private boolean evaluateItem(GameSession session, String itemId) {
        return session.getPlayer().hasItem(itemId);
    }

    private boolean evaluateRevelation(GameSession session, String threshold) {
        try {
            return session.getPlayer().getRevelation() >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateTurn(GameSession session, String threshold) {
        try {
            return session.getTurn() >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateNpcDialogueCount(GameSession session, String npcId, String threshold) {
        try {
            return session.getNpcDialogueCount(npcId) >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateAffection(GameSession session, String threshold) {
        try {
            return session.getPlayer().getAffection() >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean evaluateOr(GameSession session, String condition) {
        if (condition == null || condition.isBlank()) return true;
        if (condition.contains("|")) {
            for (String part : condition.split("\\|")) {
                if (evaluateAnd(session, part.trim())) return true;
            }
            return false;
        }
        return evaluateAnd(session, condition);
    }

    public boolean evaluateAnd(GameSession session, String condition) {
        if (condition == null || condition.isBlank()) return true;
        if (condition.contains("&")) {
            for (String part : condition.split("&")) {
                if (!evaluate(session, part.trim())) return false;
            }
            return true;
        }
        return evaluate(session, condition);
    }
}
