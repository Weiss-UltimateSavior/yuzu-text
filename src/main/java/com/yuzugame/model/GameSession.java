package com.yuzugame.model;

import java.util.*;

/**
 * 游戏会话 —— 单局游戏的完整运行时状态。
 *
 * <p>每个 GameSession 实例对应一次完整的游戏流程，包含：
 * <ul>
 *   <li>会话标识与基础状态（sessionId、turn、gamePhase）</li>
 *   <li>玩家数据（{@link Player}）</li>
 *   <li>地图与章节进度（currentMapId、currentChapter）</li>
 *   <li>谜题状态（已解决/已失败/活跃/尝试次数）</li>
 *   <li>NPC 状态（已解锁/已击杀/好感度/已复活）</li>
 *   <li>物品状态（已发现物品）</li>
 *   <li>柚子背包（独立于玩家背包的物品存储）</li>
 *   <li>对话历史（{@link ChatMessage}）</li>
 *   <li>结局状态（ended、endingType）</li>
 * </ul></p>
 *
 * <p>总分计算公式：{@code sanity + revelation + affection + aliveNpcCount * 10}</p>
 *
 * <p>游戏阶段（gamePhase）：
 * <ul>
 *   <li>{@code opening} —— 开场阶段，等待玩家首次交互</li>
 *   <li>{@code playing} —— 正式游戏阶段</li>
 *   <li>{@code ending} —— 结局阶段</li>
 * </ul></p>
 */
public class GameSession {

    private String sessionId;
    private Player player = new Player();
    private String currentMapId;
    private String currentChapter;
    private int turn = 0;
    private String gamePhase = "opening";
    private String activePuzzleId;
    private boolean mapAutoTriggered = false;
    private boolean exitUnlocked = false;
    private int mapEntryTurn = 0;

    private Set<String> solvedPuzzles = new HashSet<>();
    private Set<String> failedPuzzles = new HashSet<>();
    private Set<String> unlockedNpcs = new HashSet<>();
    private Set<String> killedNpcs = new HashSet<>();
    private Set<String> foundItems = new HashSet<>();
    private Set<Integer> triggeredSanityWarnings = new HashSet<>();

    private List<String> yuzuInventory = new ArrayList<>();
    private Set<String> revivedNpcs = new HashSet<>();

    private Map<String, Integer> puzzleAttempts = new HashMap<>();
    private Map<String, Integer> npcDialogueCounts = new HashMap<>();
    private Map<String, String> dynamicItemNames = new HashMap<>();

    private List<ChatMessage> chatHistory = new ArrayList<>();
    private boolean ended = false;
    private String endingType;

    private Map<String, Integer> usedRedemptionCodes = new HashMap<>();

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public String getCurrentMapId() { return currentMapId; }
    public void setCurrentMapId(String currentMapId) { this.currentMapId = currentMapId; }

    public String getCurrentChapter() { return currentChapter; }
    public void setCurrentChapter(String currentChapter) { this.currentChapter = currentChapter; }

    public int getTurn() { return turn; }
    public void setTurn(int turn) { this.turn = turn; }
    public void incrementTurn() { this.turn++; }

    public String getGamePhase() { return gamePhase; }
    public void setGamePhase(String gamePhase) { this.gamePhase = gamePhase; }

    public String getActivePuzzleId() { return activePuzzleId; }
    public void setActivePuzzleId(String activePuzzleId) { this.activePuzzleId = activePuzzleId; }

    public boolean isMapAutoTriggered() { return mapAutoTriggered; }
    public void setMapAutoTriggered(boolean mapAutoTriggered) { this.mapAutoTriggered = mapAutoTriggered; }

    public boolean isExitUnlocked() { return exitUnlocked; }
    public void setExitUnlocked(boolean exitUnlocked) { this.exitUnlocked = exitUnlocked; }

    public int getMapEntryTurn() { return mapEntryTurn; }
    public void setMapEntryTurn(int mapEntryTurn) { this.mapEntryTurn = mapEntryTurn; }
    public int getMapTurns() { return turn - mapEntryTurn; }

    public Set<String> getSolvedPuzzles() { return solvedPuzzles; }
    public boolean isPuzzleSolved(String puzzleId) { return solvedPuzzles.contains(puzzleId); }
    public void markPuzzleSolved(String puzzleId) { solvedPuzzles.add(puzzleId); }

    public Set<String> getFailedPuzzles() { return failedPuzzles; }
    public void markPuzzleFailed(String puzzleId) { failedPuzzles.add(puzzleId); }

    public Set<String> getUnlockedNpcs() { return unlockedNpcs; }
    public boolean isNpcUnlocked(String npcId) { return unlockedNpcs.contains(npcId); }
    public void unlockNpc(String npcId) { unlockedNpcs.add(npcId); }

    public Set<String> getKilledNpcs() { return killedNpcs; }
    public boolean isNpcKilled(String npcId) { return killedNpcs.contains(npcId); }
    public void killNpc(String npcId) { killedNpcs.add(npcId); }

    public Set<String> getFoundItems() { return foundItems; }
    public boolean isItemFound(String itemId) { return foundItems.contains(itemId); }
    public void foundItem(String itemId) { foundItems.add(itemId); }

    public Map<String, String> getDynamicItemNames() { return dynamicItemNames; }
    public void setDynamicItemNames(Map<String, String> dynamicItemNames) { this.dynamicItemNames = dynamicItemNames; }
    public void registerDynamicItemName(String itemId, String name) { dynamicItemNames.put(itemId, name); }
    public String getDynamicItemName(String itemId) { return dynamicItemNames.get(itemId); }

    public int getPuzzleAttempts(String puzzleId) { return puzzleAttempts.getOrDefault(puzzleId, 0); }
    public Map<String, Integer> getPuzzleAttempts() { return puzzleAttempts; }
    public int incrementPuzzleAttempts(String puzzleId) { return puzzleAttempts.merge(puzzleId, 1, Integer::sum); }

    public int getNpcDialogueCount(String npcId) { return npcDialogueCounts.getOrDefault(npcId, 0); }
    public Map<String, Integer> getNpcDialogueCounts() { return npcDialogueCounts; }
    public int incrementNpcDialogueCount(String npcId) { return npcDialogueCounts.merge(npcId, 1, Integer::sum); }

    public List<ChatMessage> getChatHistory() { return chatHistory; }
    public void addChatMessage(ChatMessage msg) { chatHistory.add(msg); }

    public boolean isEnded() { return ended; }
    public void setEnded(boolean ended) { this.ended = ended; }

    public String getEndingType() { return endingType; }
    public void setEndingType(String endingType) { this.endingType = endingType; }

    /**
     * 计算当前存活的 NPC 数量（已解锁但未被击杀）。
     */
    public int getAliveNpcCount() {
        return (int) unlockedNpcs.stream().filter(id -> !killedNpcs.contains(id)).count();
    }

    /**
     * 计算当前总分 —— 用于结局判定。
     *
     * <p>公式：{@code sanity + revelation + affection + aliveNpcCount * 10}</p>
     */
    public int getScore() {
        return player.getSanity() + player.getRevelation() + player.getAffection() + getAliveNpcCount() * 10;
    }

    public Set<Integer> getTriggeredSanityWarnings() { return triggeredSanityWarnings; }

    public List<String> getYuzuInventory() { return yuzuInventory; }
    public boolean yuzuHasItem(String itemId) { return yuzuInventory.contains(itemId); }
    public void addYuzuItem(String itemId) { if (!yuzuInventory.contains(itemId)) yuzuInventory.add(itemId); }
    public void removeYuzuItem(String itemId) { yuzuInventory.remove(itemId); }

    public boolean isNpcRevived(String npcId) { return revivedNpcs.contains(npcId); }
    public void reviveNpc(String npcId) { revivedNpcs.add(npcId); killedNpcs.remove(npcId); }
    public Set<String> getRevivedNpcs() { return revivedNpcs; }

    public Map<String, Integer> getUsedRedemptionCodes() { return usedRedemptionCodes; }
    public int getRedemptionCodeUseCount(String code) { return usedRedemptionCodes.getOrDefault(normalizeCode(code), 0); }
    public void recordRedemptionCodeUse(String code) { usedRedemptionCodes.merge(normalizeCode(code), 1, Integer::sum); }

    private static String normalizeCode(String code) {
        if (code == null) return null;
        StringBuilder sb = new StringBuilder(code.length());
        for (char c : code.toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append((char) (c - 32));
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 对话消息记录 —— 用于维护对话上下文。
     *
     * @param senderType 发送者类型：PLAYER / PROTAGONIST_AI / NPC_AI / DIRECTOR_AI / MAP_AI / PUZZLE_AI
     * @param npcId NPC ID（仅 NPC_AI 类型有效）
     * @param content 消息内容
     */
    public record ChatMessage(String senderType, String npcId, String content) {}
}
