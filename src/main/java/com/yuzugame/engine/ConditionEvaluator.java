package com.yuzugame.engine;

import com.yuzugame.model.GameSession;
import org.springframework.stereotype.Component;

@Component
public class ConditionEvaluator {

    public boolean evaluate(GameSession session, String condition) {
        if (condition == null || condition.isBlank()) {
            return true;
        }

        String[] parts = condition.split(":", 3);
        if (parts.length < 2) return false;

        String type = parts[0];
        if (type.isBlank()) return false;

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
        if (chapterId == null || chapterId.isBlank()) return false;
        String current = session.getCurrentChapter();
        return chapterId.equals(current);
    }

    private boolean evaluatePuzzle(GameSession session, String puzzleId, String status) {
        if (puzzleId == null || puzzleId.isBlank()) return false;
        if ("success".equals(status)) {
            return session.isPuzzleSolved(puzzleId);
        }
        if ("failed".equals(status)) {
            return session.getFailedPuzzles().contains(puzzleId);
        }
        return false;
    }

    private boolean evaluateItem(GameSession session, String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        return session.getPlayer().hasItem(itemId);
    }

    private boolean evaluateRevelation(GameSession session, String threshold) {
        if (threshold == null || threshold.isBlank()) return false;
        try {
            return session.getPlayer().getRevelation() >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateTurn(GameSession session, String threshold) {
        if (threshold == null || threshold.isBlank()) return false;
        try {
            return session.getTurn() >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateNpcDialogueCount(GameSession session, String npcId, String threshold) {
        if (npcId == null || npcId.isBlank() || threshold == null || threshold.isBlank()) return false;
        try {
            return session.getNpcDialogueCount(npcId) >= Integer.parseInt(threshold);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateAffection(GameSession session, String threshold) {
        if (threshold == null || threshold.isBlank()) return false;
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
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                if (evaluateAnd(session, trimmed)) return true;
            }
            return false;
        }
        return evaluateAnd(session, condition);
    }

    public boolean evaluateAnd(GameSession session, String condition) {
        if (condition == null || condition.isBlank()) return true;
        if (condition.contains("&")) {
            for (String part : condition.split("&")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                if (!evaluate(session, trimmed)) return false;
            }
            return true;
        }
        return evaluate(session, condition);
    }
}
