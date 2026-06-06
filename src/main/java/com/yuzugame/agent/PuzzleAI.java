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

    private String chatWithSession(GameSession session, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        if (session.hasCustomLlm()) {
            return llm.chat(systemPrompt, userMessage, history, session.getCustomLlmBaseUrl(), session.getCustomLlmApiKey(), session.getCustomLlmModel());
        }
        return llm.chat(systemPrompt, userMessage, history);
    }

    public String handle(GameSession session, PuzzleConfig puzzle, MapConfig currentMap, String playerMessage) {
        if (puzzle == null || currentMap == null) return "";
        String prompt = buildPrompt(session, puzzle, currentMap);

        List<Map<String, String>> history = new ArrayList<>();
        for (PuzzleMemoryEntry entry : session.getPuzzleMemoryEntries(puzzle.getId())) {
            if (entry.role() != null && entry.content() != null) {
                history.add(Map.of("role", entry.role(), "content", entry.content()));
            }
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
                String content = msg.content();
                if (content != null && !content.isBlank()) {
                    history.add(Map.of("role", role, "content", label + content));
                }
            }
        }

        String response = chatWithSession(session, prompt, nullToEmpty(playerMessage), history.isEmpty() ? null : history);

        session.addPuzzleMemoryEntry(puzzle.getId(), new PuzzleMemoryEntry("user", nullToEmpty(playerMessage)));
        session.addPuzzleMemoryEntry(puzzle.getId(), new PuzzleMemoryEntry("assistant", response));

        int maxRounds = gameConfig().getMaxPuzzleMemoryRounds();
        session.truncatePuzzleMemory(puzzle.getId(), maxRounds);

        return response;
    }

    private String buildPrompt(GameSession session, PuzzleConfig puzzle, MapConfig currentMap) {
        StringBuilder sb = new StringBuilder();
        appendNonNull(sb, puzzle.getSystemPrompt());

        sb.append("\n\n=== 场景信息 ===\n");
        sb.append("地图: ").append(nullToEmpty(currentMap.getName())).append("\n");
        sb.append("章节: ").append(nullToEmpty(currentMap.getChapterName())).append("\n");
        sb.append("气氛: ").append(nullToEmpty(currentMap.getAtmosphere())).append("\n");
        sb.append("当前回合: ").append(session.getTurn()).append(" (本地图内: ").append(session.getMapTurns()).append(")\n");
        if (session.isPuzzleSolved(puzzle.getId())) {
            sb.append("出口: ").append(nullToEmpty(currentMap.getExitHint())).append("\n");
        }

        List<Map<String, String>> areas = currentMap.getAreas();
        if (areas != null && !areas.isEmpty()) {
            sb.append("\n=== 区域（与地图AI一致） ===\n");
            int idx = 1;
            for (Map<String, String> area : areas) {
                sb.append(idx++).append(". ").append(nullToEmpty(area.get("name"))).append("：").append(nullToEmpty(area.get("description"))).append("\n");
            }
        }

        String stateHeader = safeReplace(prompts().getStateHeader(),
                "{puzzleName}", nullToEmpty(puzzle.getName()),
                "{difficulty}", String.valueOf(puzzle.getDifficulty()),
                "{description}", nullToEmpty(puzzle.getDescription()),
                "{solutionCriteria}", nullToEmpty(puzzle.getSolutionCriteria()),
                "{maxAttempts}", String.valueOf(puzzle.getMaxAttempts()),
                "{attempts}", String.valueOf(session.getPuzzleAttempts(puzzle.getId())),
                "{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()),
                "{failNarrative}", nullToEmpty(puzzle.getFailNarrative()));
        sb.append("\n\n").append(stateHeader).append("\n");

        if (session.getCurrentArea() != null) {
            sb.append("玩家当前所在区域: ").append(session.getCurrentArea()).append("\n");
            sb.append("!!! 重要：玩家当前已经在「").append(session.getCurrentArea()).append("」区域，你的叙事必须从该区域开始，不要让玩家回到起点或之前的区域！\n");
        }

        if (puzzle.getRequiredItemId() != null) {
            String ownedLabel = prompts().getOwnedLabel();
            String missingLabel = prompts().getMissingLabel();
            String itemStatus = safeReplace(prompts().getRequiredItemTemplate(),
                    "{itemId}", puzzle.getRequiredItemId(),
                    "{status}", session.getPlayer().hasItem(puzzle.getRequiredItemId()) ? nullToEmpty(ownedLabel) : nullToEmpty(missingLabel));
            sb.append(itemStatus).append("\n");
        }

        if (!session.getPlayer().getInventory().isEmpty()) {
            sb.append("\n玩家持有的物品:\n");
            for (String itemId : session.getPlayer().getInventory()) {
                String displayName = resolveItemName(session, itemId);
                sb.append("- ").append(itemId).append(displayName.equals(itemId) ? "" : "(" + displayName + ")").append("\n");
            }
        }

        String turnAndSanity = safeReplace(prompts().getTurnAndSanityTemplate(),
                "{turn}", String.valueOf(session.getTurn()),
                "{sanity}", String.valueOf(session.getPlayer().getSanity()));
        appendSection(sb, turnAndSanity);

        int attempts = session.getPuzzleAttempts(puzzle.getId());
        if (attempts >= puzzle.getMaxAttempts()) {
            String failNotice = safeReplace(prompts().getMaxAttemptsReachedTemplate(),
                    "{puzzleId}", nullToEmpty(puzzle.getId()),
                    "{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()));
            appendSection(sb, failNotice);
        }

        int minRounds = gameConfig().getPuzzleMinRoundsBase() + puzzle.getDifficulty();
        int currentRounds = session.getPuzzleMemoryEntries(puzzle.getId()).stream()
                .filter(e -> "assistant".equals(e.role()))
                .mapToInt(e -> 1)
                .sum();

        String rules = safeReplace(prompts().getSolvingRules(),
                "{minRounds}", String.valueOf(minRounds),
                "{difficulty}", String.valueOf(puzzle.getDifficulty()),
                "{currentRounds}", String.valueOf(currentRounds),
                "{maxAttempts}", String.valueOf(puzzle.getMaxAttempts()),
                "{failSanityPenalty}", String.valueOf(puzzle.getFailSanityPenalty()));
        appendSection(sb, rules);

        String availableTags = safeReplace(prompts().getAvailableTagsTemplate(),
                "{puzzleId}", nullToEmpty(puzzle.getId()));
        sb.append("\n").append(availableTags);
        if (puzzle.getRequiredItemId() != null) {
            String itemTakeTag = safeReplace(prompts().getItemTakeTagTemplate(),
                    "{itemId}", puzzle.getRequiredItemId());
            sb.append(itemTakeTag);
        }

        String archiveMapId = gameConfig().getArchiveMapId();
        if (archiveMapId != null && archiveMapId.equals(currentMap.getId())) {
            String archiveRule = dataLoader.getPrompts().getMap().getArchiveSpecialRule();
            appendSection(sb, archiveRule);
        }

        List<String> mapNpcIds = currentMap.getNpcIds() != null ? currentMap.getNpcIds() : Collections.emptyList();
        List<String> stillLocked = new ArrayList<>(mapNpcIds);
        stillLocked.removeAll(session.getUnlockedNpcs());
        stillLocked.removeAll(session.getKilledNpcs());
        if (!stillLocked.isEmpty()) {
            String npcUnlock = prompts().getNpcUnlockSection();
            if (npcUnlock != null) {
                StringBuilder npcList = new StringBuilder();
                for (String id : stillLocked) {
                    NpcConfig npcCfg = dataLoader.getNpc(id);
                    if (npcCfg != null) {
                        npcList.append(id).append("(").append(nullToEmpty(npcCfg.getName())).append(") ");
                    } else {
                        npcList.append(id).append(" ");
                    }
                }
                sb.append("\n\n").append(npcUnlock.replace("{npcList}", npcList.toString().trim())).append("\n");
            }
        }

        String ctrlTagExample = safeReplace(prompts().getCtrlTagExample(),
                "{puzzleId}", nullToEmpty(puzzle.getId()));
        sb.append("\n\n").append(ctrlTagExample).append("\n");

        if (puzzle.getRequiredItemId() != null) {
            sb.append("\n\n有前置物品的解谜成功示例（必须同时消耗物品）：\n<ctrl>\nPUZZLE:SOLVE:").append(puzzle.getId()).append("\nITEM:TAKE:").append(puzzle.getRequiredItemId()).append("\nREVELATION:+5\n</ctrl>\n");
        }

        if (!stillLocked.isEmpty()) {
            String solveWithNpc = safeReplace(prompts().getSolveWithNpcExample(),
                    "{puzzleId}", nullToEmpty(puzzle.getId()),
                    "{npcId}", stillLocked.get(0));
            sb.append("\n\n").append(solveWithNpc).append("\n");
        }

        return sb.toString();
    }

    private String resolveItemName(GameSession session, String itemId) {
        String dynamicName = session.getDynamicItemName(itemId);
        if (dynamicName != null) return dynamicName;
        ItemConfig item = dataLoader.getItem(itemId);
        return item != null ? nullToEmpty(item.getName()) : itemId;
    }

    private List<GameSession.ChatMessage> buildRecentContext(GameSession session) {
        List<GameSession.ChatMessage> all = session.getChatHistory();
        int limit = Math.min(gameConfig().getMapAiHistoryLimit(), all.size());
        int start = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(start, all.size()));
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String safeReplace(String template, String... pairs) {
        if (template == null) return "";
        String result = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace(pairs[i], pairs[i + 1] != null ? pairs[i + 1] : "");
        }
        return result;
    }

    private static void appendSection(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            sb.append("\n").append(text).append("\n");
        }
    }

    private static void appendNonNull(StringBuilder sb, String text) {
        if (text != null) {
            sb.append(text);
        }
    }
}
