package com.yuzugame.model;

import com.yuzugame.util.CodeUtils;

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

    /** 聊天历史最大条目数，超出时自动裁剪旧消息 */
    private static final int MAX_CHAT_HISTORY = 200;

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
    private String currentArea;

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

    private Map<String, List<PuzzleMemoryEntry>> puzzleMemory = new HashMap<>();

    private String customLlmBaseUrl;
    private String customLlmApiKey;
    private String customLlmModel;

    public String getCustomLlmBaseUrl() { return customLlmBaseUrl; }
    public void setCustomLlmBaseUrl(String v) { this.customLlmBaseUrl = v; }
    public String getCustomLlmApiKey() { return customLlmApiKey; }
    public void setCustomLlmApiKey(String v) { this.customLlmApiKey = v; }
    public String getCustomLlmModel() { return customLlmModel; }
    public void setCustomLlmModel(String v) { this.customLlmModel = v; }
    public boolean hasCustomLlm() {
        return customLlmBaseUrl != null && !customLlmBaseUrl.isBlank()
                && customLlmApiKey != null && !customLlmApiKey.isBlank()
                && customLlmModel != null && !customLlmModel.isBlank();
    }

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

    public String getCurrentArea() { return currentArea; }
    public void setCurrentArea(String currentArea) { this.currentArea = currentArea; }

    public Set<String> getSolvedPuzzles() { return Collections.unmodifiableSet(solvedPuzzles); }
    public void setSolvedPuzzles(Set<String> solvedPuzzles) { this.solvedPuzzles = new HashSet<>(solvedPuzzles); }
    public boolean isPuzzleSolved(String puzzleId) { return solvedPuzzles.contains(puzzleId); }
    public void markPuzzleSolved(String puzzleId) { solvedPuzzles.add(puzzleId); }

    public Set<String> getFailedPuzzles() { return Collections.unmodifiableSet(failedPuzzles); }
    public void setFailedPuzzles(Set<String> failedPuzzles) { this.failedPuzzles = new HashSet<>(failedPuzzles); }
    public void markPuzzleFailed(String puzzleId) { failedPuzzles.add(puzzleId); }

    public Set<String> getUnlockedNpcs() { return Collections.unmodifiableSet(unlockedNpcs); }
    public void setUnlockedNpcs(Set<String> unlockedNpcs) { this.unlockedNpcs = new HashSet<>(unlockedNpcs); }
    public boolean isNpcUnlocked(String npcId) { return unlockedNpcs.contains(npcId); }
    public void unlockNpc(String npcId) { unlockedNpcs.add(npcId); }

    public Set<String> getKilledNpcs() { return Collections.unmodifiableSet(killedNpcs); }
    public void setKilledNpcs(Set<String> killedNpcs) { this.killedNpcs = new HashSet<>(killedNpcs); }
    public boolean isNpcKilled(String npcId) { return killedNpcs.contains(npcId); }
    public void killNpc(String npcId) { killedNpcs.add(npcId); }

    public Set<String> getFoundItems() { return Collections.unmodifiableSet(foundItems); }
    public void setFoundItems(Set<String> foundItems) { this.foundItems = new HashSet<>(foundItems); }
    public boolean isItemFound(String itemId) { return foundItems.contains(itemId); }
    public void foundItem(String itemId) { foundItems.add(itemId); }

    public Map<String, String> getDynamicItemNames() { return Collections.unmodifiableMap(dynamicItemNames); }
    public void setDynamicItemNames(Map<String, String> dynamicItemNames) { this.dynamicItemNames = new HashMap<>(dynamicItemNames); }
    public void registerDynamicItemName(String itemId, String name) { dynamicItemNames.put(itemId, name); }
    public String getDynamicItemName(String itemId) { return dynamicItemNames.get(itemId); }

    public int getPuzzleAttempts(String puzzleId) { return puzzleAttempts.getOrDefault(puzzleId, 0); }
    public Map<String, Integer> getPuzzleAttempts() { return Collections.unmodifiableMap(puzzleAttempts); }
    public void setPuzzleAttempts(Map<String, Integer> puzzleAttempts) { this.puzzleAttempts = new HashMap<>(puzzleAttempts); }
    public int incrementPuzzleAttempts(String puzzleId) { return puzzleAttempts.merge(puzzleId, 1, Integer::sum); }

    public int getNpcDialogueCount(String npcId) { return npcDialogueCounts.getOrDefault(npcId, 0); }
    public Map<String, Integer> getNpcDialogueCounts() { return Collections.unmodifiableMap(npcDialogueCounts); }
    public void setNpcDialogueCounts(Map<String, Integer> npcDialogueCounts) { this.npcDialogueCounts = new HashMap<>(npcDialogueCounts); }
    public int incrementNpcDialogueCount(String npcId) { return npcDialogueCounts.merge(npcId, 1, Integer::sum); }

    public List<ChatMessage> getChatHistory() { return Collections.unmodifiableList(chatHistory); }
    public void setChatHistory(List<ChatMessage> chatHistory) { this.chatHistory = new ArrayList<>(chatHistory); }
    public void addChatMessage(ChatMessage msg) {
        chatHistory.add(msg);
        // 限制聊天历史最大条目数，避免内存和存储膨胀
        if (chatHistory.size() > MAX_CHAT_HISTORY) {
            chatHistory.subList(0, chatHistory.size() - MAX_CHAT_HISTORY).clear();
        }
    }

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

    public Set<Integer> getTriggeredSanityWarnings() { return Collections.unmodifiableSet(triggeredSanityWarnings); }
    public void setTriggeredSanityWarnings(Set<Integer> triggeredSanityWarnings) { this.triggeredSanityWarnings = new HashSet<>(triggeredSanityWarnings); }
    public void triggerSanityWarning(int threshold) { triggeredSanityWarnings.add(threshold); }

    public List<String> getYuzuInventory() { return Collections.unmodifiableList(yuzuInventory); }
    public void setYuzuInventory(List<String> yuzuInventory) { this.yuzuInventory = new ArrayList<>(yuzuInventory); }
    public boolean yuzuHasItem(String itemId) { return yuzuInventory.contains(itemId); }
    public void addYuzuItem(String itemId) { if (!yuzuInventory.contains(itemId)) yuzuInventory.add(itemId); }
    public void removeYuzuItem(String itemId) { yuzuInventory.remove(itemId); }

    public boolean isNpcRevived(String npcId) { return revivedNpcs.contains(npcId); }
    public void reviveNpc(String npcId) { revivedNpcs.add(npcId); killedNpcs.remove(npcId); }
    public Set<String> getRevivedNpcs() { return Collections.unmodifiableSet(revivedNpcs); }
    public void setRevivedNpcs(Set<String> revivedNpcs) { this.revivedNpcs = new HashSet<>(revivedNpcs); }

    public Map<String, Integer> getUsedRedemptionCodes() { return Collections.unmodifiableMap(usedRedemptionCodes); }
    public void setUsedRedemptionCodes(Map<String, Integer> usedRedemptionCodes) { this.usedRedemptionCodes = new HashMap<>(usedRedemptionCodes); }
    public int getRedemptionCodeUseCount(String code) { return usedRedemptionCodes.getOrDefault(normalizeCode(code), 0); }
    public void recordRedemptionCodeUse(String code) { usedRedemptionCodes.merge(normalizeCode(code), 1, Integer::sum); }

    private static String normalizeCode(String code) {
        return CodeUtils.normalizeCode(code);
    }

    public Map<String, List<PuzzleMemoryEntry>> getPuzzleMemory() { return Collections.unmodifiableMap(puzzleMemory); }
    public void setPuzzleMemory(Map<String, List<PuzzleMemoryEntry>> puzzleMemory) { this.puzzleMemory = new HashMap<>(puzzleMemory); }

    public List<PuzzleMemoryEntry> getPuzzleMemoryEntries(String puzzleId) {
        return puzzleMemory.getOrDefault(puzzleId, List.of());
    }

    public void addPuzzleMemoryEntry(String puzzleId, PuzzleMemoryEntry entry) {
        puzzleMemory.computeIfAbsent(puzzleId, k -> new ArrayList<>()).add(entry);
    }

    public void truncatePuzzleMemory(String puzzleId, int maxRounds) {
        List<PuzzleMemoryEntry> entries = puzzleMemory.get(puzzleId);
        if (entries != null && entries.size() > maxRounds * 2) {
            puzzleMemory.put(puzzleId, new ArrayList<>(entries.subList(entries.size() - maxRounds * 2, entries.size())));
        }
    }

    public void clearPuzzleMemory(String puzzleId) {
        puzzleMemory.remove(puzzleId);
    }

    public void clearAllPuzzleMemory() {
        puzzleMemory.clear();
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
