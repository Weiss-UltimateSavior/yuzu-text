package com.yuzugame.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.model.GameConfig;
import com.yuzugame.model.GameSession;
import com.yuzugame.model.PuzzleMemoryEntry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class GameEngineTest {

    @Test
    void exploreKeywords_doesNotMatchEmptyOrUnrelatedInput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("data/game_config.json")) {
            assertNotNull(is);
            GameConfig config = mapper.readValue(is, GameConfig.class);
            Pattern pattern = Pattern.compile(config.getExploreKeywords());

            assertFalse(pattern.matcher("").find());
            assertFalse(pattern.matcher("你好，柚子").find());
            assertTrue(pattern.matcher("观察周围").find());
        }
    }

    @Test
    void recordPuzzleMemory_recordsConversationAndTruncatesByRounds() {
        GameSession session = new GameSession();
        String puzzleId = "puzzle_1";

        GameEngine.recordPuzzleMemory(session, puzzleId, "第一次尝试", "谜题回应1", 2);
        GameEngine.recordPuzzleMemory(session, puzzleId, "第二次尝试", "谜题回应2", 2);
        GameEngine.recordPuzzleMemory(session, puzzleId, "第三次尝试", "谜题回应3", 2);

        List<PuzzleMemoryEntry> memory = session.getPuzzleMemoryEntries(puzzleId);
        assertEquals(4, memory.size());
        assertEquals(new PuzzleMemoryEntry("user", "第二次尝试"), memory.get(0));
        assertEquals(new PuzzleMemoryEntry("assistant", "谜题回应2"), memory.get(1));
        assertEquals(new PuzzleMemoryEntry("user", "第三次尝试"), memory.get(2));
        assertEquals(new PuzzleMemoryEntry("assistant", "谜题回应3"), memory.get(3));
    }
}
