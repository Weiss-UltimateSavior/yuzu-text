package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PuzzleAI {

    private final LlmService llm;
    private final GameDataLoader dataLoader;

    private final Map<String, List<String>> puzzleMemory = new ConcurrentHashMap<>(); // 谜题专属对话记忆

    public PuzzleAI(LlmService llm, GameDataLoader dataLoader) {
        this.llm = llm;
        this.dataLoader = dataLoader;
    }

    private PromptsConfig.PuzzlePrompts prompts() {
        return dataLoader.getPrompts().getPuzzle();
    }

    private GameConfig gameConfig() {
        return dataLoader.getGameConfig();
    }

    public void clearSessionMemory(String sessionId) { // 会话结束时清理所有谜题记忆
        puzzleMemory.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
    }

    public void clearPuzzleMemory(String sessionId, String puzzleId) { // 谜题解决后清理该谜题记忆
        puzzleMemory.remove(sessionId + ":" + puzzleId);
    }

    public String handle(GameSession session, PuzzleConfig puzzle, MapConfig currentMap, String playerMessage) {
        String prompt = buildPrompt(session, puzzle, currentMap);
        String key = session.getSessionId() + ":" + puzzle.getId();

        List<Map<String, String>> history = new ArrayList<>();
        List<String> memory = puzzleMemory.getOrDefault(key, List.of());
        for (String m : memory) { // 将历史记忆作为assistant消息注入
            history.add(Map.of("role", "assistant", "content", m));
        }

        String response = llm.chat(prompt, playerMessage, history.isEmpty() ? null : history);

        puzzleMemory.computeIfAbsent(key, k -> new ArrayList<>()).add(response); // 记录本轮回复

        int maxRounds = gameConfig().getMaxPuzzleMemoryRounds(); // 超出上限时截断旧记忆
        if (puzzleMemory.get(key).size() > maxRounds) {
            List<String> old = puzzleMemory.get(key);
            puzzleMemory.put(key, new ArrayList<>(old.subList(old.size() - maxRounds, old.size())));
        }

        return response;
    }

    private String buildPrompt(GameSession session, PuzzleConfig puzzle, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder(puzzle.getSystemPrompt());

        String stateHeader = prompts().getStateHeader()
                .replace("{puzzleName}", puzzle.getName())
                .replace("{difficulty}", String.valueOf(puzzle.getDifficulty()))
                .replace("{description}", puzzle.getDescription())
                .replace("{solutionCriteria}", puzzle.getSolutionCriteria())
                .replace("{maxAttempts}", String.valueOf(puzzle.getMaxAttempts()))
                .replace("{attempts}", String.valueOf(session.getPuzzleAttempts(puzzle.getId())))
                .replace("{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()))
                .replace("{failNarrative}", puzzle.getFailNarrative());
        sb.append("\n\n").append(stateHeader).append("\n");

        if (puzzle.getRequiredItemId() != null) { // 需要特定物品才能解谜
            String itemStatus = prompts().getRequiredItemTemplate()
                    .replace("{itemId}", puzzle.getRequiredItemId())
                    .replace("{status}", session.getPlayer().hasItem(puzzle.getRequiredItemId()) ? prompts().getOwnedLabel() : prompts().getMissingLabel());
            sb.append(itemStatus).append("\n");
        }

        sb.append("\n").append(prompts().getTurnAndSanityTemplate()
                .replace("{turn}", String.valueOf(session.getTurn()))
                .replace("{sanity}", String.valueOf(session.getPlayer().getSanity()))).append("\n");

        int attempts = session.getPuzzleAttempts(puzzle.getId());
        if (attempts >= puzzle.getMaxAttempts()) { // 已达最大尝试次数，提示FAIL
            String failNotice = prompts().getMaxAttemptsReachedTemplate()
                    .replace("{puzzleId}", puzzle.getId())
                    .replace("{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()));
            sb.append("\n").append(failNotice).append("\n");
        }

        int minRounds = gameConfig().getPuzzleMinRoundsBase() + puzzle.getDifficulty(); // 最少交互轮数 = 基础 + 难度
        int currentRounds = puzzleMemory.getOrDefault(session.getSessionId() + ":" + puzzle.getId(), List.of()).size();

        String rules = prompts().getSolvingRules()
                .replace("{minRounds}", String.valueOf(minRounds))
                .replace("{difficulty}", String.valueOf(puzzle.getDifficulty()))
                .replace("{currentRounds}", String.valueOf(currentRounds));
        sb.append("\n").append(rules).append("\n");

        sb.append("\n").append(prompts().getAvailableTagsTemplate()
                .replace("{puzzleId}", puzzle.getId()));
        if (puzzle.getRequiredItemId() != null) { // 需要物品时追加ITEM:TAKE标签
            sb.append(prompts().getItemTakeTagTemplate()
                    .replace("{itemId}", puzzle.getRequiredItemId()));
        }

        List<String> stillLocked = new ArrayList<>(currentMap.getNpcIds());
        stillLocked.removeAll(session.getUnlockedNpcs());
        stillLocked.removeAll(session.getKilledNpcs());
        if (!stillLocked.isEmpty()) { // 未解锁NPC：谜题解决时可一并解锁
            String npcUnlock = prompts().getNpcUnlockSection();
            StringBuilder npcList = new StringBuilder();
            for (String id : stillLocked) {
                npcList.append(id).append(" ");
            }
            sb.append("\n\n").append(npcUnlock.replace("{npcList}", npcList.toString().trim())).append("\n");
        }

        sb.append("\n\n").append(prompts().getCtrlTagExample()
                .replace("{puzzleId}", puzzle.getId())).append("\n");

        if (!stillLocked.isEmpty()) { // 附带NPC解锁的示例
            sb.append("\n\n").append(prompts().getSolveWithNpcExample()
                    .replace("{puzzleId}", puzzle.getId())
                    .replace("{npcId}", stillLocked.get(0))).append("\n");
        }

        return sb.toString();
    }
}
