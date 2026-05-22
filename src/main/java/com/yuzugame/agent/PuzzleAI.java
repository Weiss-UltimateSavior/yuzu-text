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

        if (currentMap.getAreas() != null && !currentMap.getAreas().isEmpty()) {
            sb.append("\n\n=== 区域（与地图AI一致） ===\n");
            int idx = 1;
            for (Map<String, String> area : currentMap.getAreas()) {
                sb.append(idx++).append(". ").append(area.get("name")).append("：").append(area.get("description")).append("\n");
            }
        }

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

        if (session.getCurrentArea() != null) {
            sb.append("玩家当前所在区域: ").append(session.getCurrentArea()).append("\n");
            sb.append("!!! 重要：玩家当前已经在「").append(session.getCurrentArea()).append("」区域，你的叙事必须从该区域开始，不要让玩家回到起点或之前的区域！\n");
        }

        if (puzzle.getRequiredItemId() != null) {
            String itemStatus = prompts().getRequiredItemTemplate()
                    .replace("{itemId}", puzzle.getRequiredItemId())
                    .replace("{status}", session.getPlayer().hasItem(puzzle.getRequiredItemId()) ? prompts().getOwnedLabel() : prompts().getMissingLabel());
            sb.append(itemStatus).append("\n");
        }

        if (!session.getPlayer().getInventory().isEmpty()) {
            sb.append("\n玩家持有的物品:\n");
            for (String itemId : session.getPlayer().getInventory()) {
                String displayName = resolveItemName(session, itemId);
                sb.append("- ").append(itemId).append(displayName.equals(itemId) ? "" : "(" + displayName + ")").append("\n");
            }
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
        if (!stillLocked.isEmpty()) {
            String npcUnlock = prompts().getNpcUnlockSection();
            StringBuilder npcList = new StringBuilder();
            for (String id : stillLocked) {
                NpcConfig npcCfg = dataLoader.getNpc(id);
                if (npcCfg != null) {
                    npcList.append(id).append("(").append(npcCfg.getName()).append(") ");
                } else {
                    npcList.append(id).append(" ");
                }
            }
            sb.append("\n\n").append(npcUnlock.replace("{npcList}", npcList.toString().trim())).append("\n");
        }

        sb.append("\n\n").append(prompts().getCtrlTagExample()
                .replace("{puzzleId}", puzzle.getId())).append("\n");

        if (puzzle.getRequiredItemId() != null) {
            sb.append("\n\n有前置物品的解谜成功示例（必须同时消耗物品）：\n<ctrl>\nPUZZLE:SOLVE:").append(puzzle.getId()).append("\nITEM:TAKE:").append(puzzle.getRequiredItemId()).append("\nREVELATION:+5\n</ctrl>\n");
        }

        if (!stillLocked.isEmpty()) { // 附带NPC解锁的示例
            sb.append("\n\n").append(prompts().getSolveWithNpcExample()
                    .replace("{puzzleId}", puzzle.getId())
                    .replace("{npcId}", stillLocked.get(0))).append("\n");
        }

        return sb.toString();
    }

    private String resolveItemName(GameSession session, String itemId) {
        String dynamicName = session.getDynamicItemName(itemId);
        if (dynamicName != null) return dynamicName;
        ItemConfig item = dataLoader.getItem(itemId);
        return item != null ? item.getName() : itemId;
    }
}
