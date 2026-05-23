package com.yuzugame.agent;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.*;
import com.yuzugame.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PuzzleAI {

    private final LlmService llm;
    private final GameDataLoader dataLoader;

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

    public void clearSessionMemory(String sessionId) {
        // no-op: memory is now stored in GameSession, cleared via clearAllPuzzleMemory()
    }

    public void clearPuzzleMemory(String sessionId, String puzzleId) {
        // no-op: memory is now stored in GameSession, cleared via GameSession.clearPuzzleMemory()
    }

    public String handle(GameSession session, PuzzleConfig puzzle, MapConfig currentMap, String playerMessage) {
        String prompt = buildPrompt(session, puzzle, currentMap);

        List<Map<String, String>> history = new ArrayList<>();
        for (PuzzleMemoryEntry entry : session.getPuzzleMemoryEntries(puzzle.getId())) {
            history.add(Map.of("role", entry.role(), "content", entry.content()));
        }

        if (history.isEmpty()) {
            List<GameSession.ChatMessage> recentContext = buildRecentContext(session);
            for (GameSession.ChatMessage msg : recentContext) {
                String role = switch (msg.senderType()) {
                    case "PLAYER" -> "user";
                    case "MAP_AI" -> "user";
                    default -> "user";
                };
                String label = switch (msg.senderType()) {
                    case "MAP_AI" -> "【场景描写】";
                    case "NPC_AI" -> "【" + (msg.npcId() != null ? msg.npcId() : "NPC") + "】";
                    case "PROTAGONIST_AI" -> "【柚子】";
                    case "DIRECTOR_AI" -> "【导演旁白】";
                    case "PLAYER" -> "";
                    default -> "";
                };
                if (!msg.content().isBlank()) {
                    history.add(Map.of("role", role, "content", label + msg.content()));
                }
            }
        }

        String response = llm.chat(prompt, playerMessage, history.isEmpty() ? null : history);

        session.addPuzzleMemoryEntry(puzzle.getId(), new PuzzleMemoryEntry("user", playerMessage));
        session.addPuzzleMemoryEntry(puzzle.getId(), new PuzzleMemoryEntry("assistant", response));

        int maxRounds = gameConfig().getMaxPuzzleMemoryRounds();
        session.truncatePuzzleMemory(puzzle.getId(), maxRounds);

        return response;
    }

    private String buildPrompt(GameSession session, PuzzleConfig puzzle, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder(puzzle.getSystemPrompt());

        sb.append("\n\n=== 场景信息 ===\n");
        sb.append("地图: ").append(currentMap.getName()).append("\n");
        sb.append("章节: ").append(currentMap.getChapterName()).append("\n");
        sb.append("气氛: ").append(currentMap.getAtmosphere()).append("\n");
        sb.append("当前回合: ").append(session.getTurn()).append(" (本地图内: ").append(session.getMapTurns()).append(")\n");
        if (session.isPuzzleSolved(puzzle.getId())) {
            sb.append("出口: ").append(currentMap.getExitHint()).append("\n");
        }

        if (currentMap.getAreas() != null && !currentMap.getAreas().isEmpty()) {
            sb.append("\n=== 区域（与地图AI一致） ===\n");
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
        if (attempts >= puzzle.getMaxAttempts()) {
            String failNotice = prompts().getMaxAttemptsReachedTemplate()
                    .replace("{puzzleId}", puzzle.getId())
                    .replace("{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()));
            sb.append("\n").append(failNotice).append("\n");
        }

        int minRounds = gameConfig().getPuzzleMinRoundsBase() + puzzle.getDifficulty();
        int currentRounds = session.getPuzzleMemoryEntries(puzzle.getId()).stream()
                .filter(e -> "assistant".equals(e.role()))
                .mapToInt(e -> 1)
                .sum();

        String rules = prompts().getSolvingRules()
                .replace("{minRounds}", String.valueOf(minRounds))
                .replace("{difficulty}", String.valueOf(puzzle.getDifficulty()))
                .replace("{currentRounds}", String.valueOf(currentRounds))
                .replace("{maxAttempts}", String.valueOf(puzzle.getMaxAttempts()))
                .replace("{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()));
        sb.append("\n").append(rules).append("\n");

        sb.append("\n").append(prompts().getAvailableTagsTemplate()
                .replace("{puzzleId}", puzzle.getId()));
        if (puzzle.getRequiredItemId() != null) {
            sb.append(prompts().getItemTakeTagTemplate()
                    .replace("{itemId}", puzzle.getRequiredItemId()));
        }

        if (gameConfig().getArchiveMapId().equals(currentMap.getId())) {
            sb.append("\n").append(dataLoader.getPrompts().getMap().getArchiveSpecialRule()).append("\n");
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

        if (!stillLocked.isEmpty()) {
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

    private List<GameSession.ChatMessage> buildRecentContext(GameSession session) {
        List<GameSession.ChatMessage> all = session.getChatHistory();
        int limit = Math.min(gameConfig().getMapAiHistoryLimit(), all.size());
        int start = Math.max(0, all.size() - limit);
        return all.subList(start, all.size());
    }
}
