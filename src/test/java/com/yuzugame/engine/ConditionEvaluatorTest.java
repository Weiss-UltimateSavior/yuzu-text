package com.yuzugame.engine;

import com.yuzugame.model.GameSession;
import com.yuzugame.model.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConditionEvaluator 单元测试 —— 验证游戏条件 DSL 的解析与求值。
 */
class ConditionEvaluatorTest {

    private ConditionEvaluator evaluator;
    private GameSession session;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionEvaluator();
        session = new GameSession();
        session.setPlayer(new Player());
        session.setCurrentChapter("chapter_1");
        session.setTurn(5);
    }

    @Test
    void evaluate_nullOrBlankCondition_returnsTrue() {
        assertTrue(evaluator.evaluate(session, null));
        assertTrue(evaluator.evaluate(session, ""));
        assertTrue(evaluator.evaluate(session, "   "));
    }

    @Test
    void evaluate_invalidFormat_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "invalid"));
        assertFalse(evaluator.evaluate(session, ":value"));
    }

    @Test
    void evaluate_unknownType_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "unknown:value"));
    }

    @Test
    void evaluate_chapter_matching() {
        assertTrue(evaluator.evaluate(session, "chapter:chapter_1"));
    }

    @Test
    void evaluate_chapter_notMatching() {
        assertFalse(evaluator.evaluate(session, "chapter:chapter_2"));
    }

    @Test
    void evaluate_chapter_emptyId_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "chapter:"));
    }

    @Test
    void evaluate_revelation_thresholdMet() {
        session.getPlayer().setRevelation(15);
        assertTrue(evaluator.evaluate(session, "revelation:10"));
    }

    @Test
    void evaluate_revelation_thresholdNotMet() {
        session.getPlayer().setRevelation(5);
        assertFalse(evaluator.evaluate(session, "revelation:10"));
    }

    @Test
    void evaluate_revelation_invalidNumber_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "revelation:abc"));
    }

    @Test
    void evaluate_turn_thresholdMet() {
        session.setTurn(10);
        assertTrue(evaluator.evaluate(session, "turn:5"));
    }

    @Test
    void evaluate_turn_thresholdNotMet() {
        session.setTurn(3);
        assertFalse(evaluator.evaluate(session, "turn:5"));
    }

    @Test
    void evaluate_turn_invalidNumber_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "turn:abc"));
    }

    @Test
    void evaluate_item_notInInventory_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "item:nonexistent_item"));
    }

    @Test
    void evaluate_item_inInventory_returnsTrue() {
        session.getPlayer().addItem("test_item");
        assertTrue(evaluator.evaluate(session, "item:test_item"));
    }

    @Test
    void evaluate_puzzle_notSolved_returnsFalse() {
        assertFalse(evaluator.evaluate(session, "puzzle:puzzle_1:success"));
    }

    @Test
    void evaluate_puzzle_solved_returnsTrue() {
        session.markPuzzleSolved("puzzle_1");
        assertTrue(evaluator.evaluate(session, "puzzle:puzzle_1:success"));
    }

    @Test
    void evaluate_puzzle_failed_returnsTrue() {
        session.markPuzzleFailed("puzzle_1");
        assertTrue(evaluator.evaluate(session, "puzzle:puzzle_1:failed"));
    }

    @Test
    void evaluate_puzzle_active_returnsTrue() {
        session.setActivePuzzleId("puzzle_1");
        assertTrue(evaluator.evaluate(session, "puzzle:puzzle_1:active"));
    }

    @Test
    void evaluate_puzzle_unknownStatus_returnsFalse() {
        session.markPuzzleSolved("puzzle_1");
        assertFalse(evaluator.evaluate(session, "puzzle:puzzle_1:unknown"));
    }
}
