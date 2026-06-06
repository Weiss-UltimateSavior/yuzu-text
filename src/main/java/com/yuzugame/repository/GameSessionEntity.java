package com.yuzugame.repository;

import com.yuzugame.model.GameSession;
import com.yuzugame.model.Player;
import com.yuzugame.model.PuzzleMemoryEntry;
import com.yuzugame.repository.JsonConverters.*;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "game_sessions")
public class GameSessionEntity {

    @Id
    @Column(length = 32)
    private String sessionId;

    @Convert(converter = PlayerConverter.class)
    @Column(columnDefinition = "JSON")
    private Player player;

    @Column(length = 64)
    private String currentMapId;

    @Column(length = 16)
    private String currentChapter;

    private int turn;

    @Column(length = 16)
    private String gamePhase;

    @Column(length = 64)
    private String activePuzzleId;

    private boolean mapAutoTriggered;

    private boolean exitUnlocked;

    private int mapEntryTurn;

    @Column(length = 64)
    private String currentArea;

    @Convert(converter = SetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<String> solvedPuzzles;

    @Convert(converter = SetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<String> failedPuzzles;

    @Convert(converter = SetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<String> unlockedNpcs;

    @Convert(converter = SetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<String> killedNpcs;

    @Convert(converter = SetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<String> foundItems;

    @Convert(converter = IntSetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<Integer> triggeredSanityWarnings;

    @Convert(converter = ListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> yuzuInventory;

    @Convert(converter = SetConverter.class)
    @Column(columnDefinition = "JSON")
    private Set<String> revivedNpcs;

    @Convert(converter = IntMapConverter.class)
    @Column(columnDefinition = "JSON")
    private Map<String, Integer> puzzleAttempts;

    @Convert(converter = IntMapConverter.class)
    @Column(columnDefinition = "JSON")
    private Map<String, Integer> npcDialogueCounts;

    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "JSON")
    private Map<String, String> dynamicItemNames;

    @Convert(converter = IntMapConverter.class)
    @Column(columnDefinition = "JSON")
    private Map<String, Integer> usedRedemptionCodes;

    @Convert(converter = PuzzleMemoryConverter.class)
    @Column(columnDefinition = "JSON")
    private Map<String, List<PuzzleMemoryEntry>> puzzleMemory;

    @Convert(converter = ChatHistoryConverter.class)
    @Column(columnDefinition = "JSON")
    private List<GameSession.ChatMessage> chatHistory;

    private boolean ended;

    @Column(length = 16)
    private String endingType;

    @Column(length = 512)
    private String customLlmBaseUrl;

    @Column(length = 256)
    private String customLlmApiKey;

    @Column(length = 128)
    private String customLlmModel;

    private Instant createdAt;

    private Instant updatedAt;

    public GameSessionEntity() {}

    public static GameSessionEntity fromModel(GameSession session) {
        GameSessionEntity e = new GameSessionEntity();
        e.sessionId = session.getSessionId();
        e.player = session.getPlayer();
        e.currentMapId = session.getCurrentMapId();
        e.currentChapter = session.getCurrentChapter();
        e.turn = session.getTurn();
        e.gamePhase = session.getGamePhase();
        e.activePuzzleId = session.getActivePuzzleId();
        e.mapAutoTriggered = session.isMapAutoTriggered();
        e.exitUnlocked = session.isExitUnlocked();
        e.mapEntryTurn = session.getMapEntryTurn();
        e.currentArea = session.getCurrentArea();
        e.solvedPuzzles = session.getSolvedPuzzles() != null ? new HashSet<>(session.getSolvedPuzzles()) : new HashSet<>();
        e.failedPuzzles = session.getFailedPuzzles() != null ? new HashSet<>(session.getFailedPuzzles()) : new HashSet<>();
        e.unlockedNpcs = session.getUnlockedNpcs() != null ? new HashSet<>(session.getUnlockedNpcs()) : new HashSet<>();
        e.killedNpcs = session.getKilledNpcs() != null ? new HashSet<>(session.getKilledNpcs()) : new HashSet<>();
        e.foundItems = session.getFoundItems() != null ? new HashSet<>(session.getFoundItems()) : new HashSet<>();
        e.triggeredSanityWarnings = session.getTriggeredSanityWarnings() != null ? new HashSet<>(session.getTriggeredSanityWarnings()) : new HashSet<>();
        e.yuzuInventory = session.getYuzuInventory() != null ? new ArrayList<>(session.getYuzuInventory()) : new ArrayList<>();
        e.revivedNpcs = session.getRevivedNpcs() != null ? new HashSet<>(session.getRevivedNpcs()) : new HashSet<>();
        e.puzzleAttempts = session.getPuzzleAttempts() != null ? new HashMap<>(session.getPuzzleAttempts()) : new HashMap<>();
        e.npcDialogueCounts = session.getNpcDialogueCounts() != null ? new HashMap<>(session.getNpcDialogueCounts()) : new HashMap<>();
        e.dynamicItemNames = session.getDynamicItemNames() != null ? new HashMap<>(session.getDynamicItemNames()) : new HashMap<>();
        e.usedRedemptionCodes = session.getUsedRedemptionCodes() != null ? new HashMap<>(session.getUsedRedemptionCodes()) : new HashMap<>();
        e.puzzleMemory = session.getPuzzleMemory() != null ? new HashMap<>(session.getPuzzleMemory()) : new HashMap<>();
        e.chatHistory = session.getChatHistory() != null ? new ArrayList<>(session.getChatHistory()) : new ArrayList<>();
        e.ended = session.isEnded();
        e.endingType = session.getEndingType();
        e.customLlmBaseUrl = session.getCustomLlmBaseUrl();
        e.customLlmApiKey = session.getCustomLlmApiKey();
        e.customLlmModel = session.getCustomLlmModel();
        e.updatedAt = Instant.now();
        return e;
    }

    public GameSession toModel() {
        GameSession s = new GameSession();
        s.setSessionId(sessionId);
        s.setPlayer(player != null ? player : new Player());
        s.setCurrentMapId(currentMapId);
        s.setCurrentChapter(currentChapter);
        s.setTurn(turn);
        s.setGamePhase(gamePhase);
        s.setActivePuzzleId(activePuzzleId);
        s.setMapAutoTriggered(mapAutoTriggered);
        s.setExitUnlocked(exitUnlocked);
        s.setMapEntryTurn(mapEntryTurn);
        s.setCurrentArea(currentArea);
        s.setSolvedPuzzles(solvedPuzzles != null ? new HashSet<>(solvedPuzzles) : Set.of());
        s.setFailedPuzzles(failedPuzzles != null ? new HashSet<>(failedPuzzles) : Set.of());
        s.setUnlockedNpcs(unlockedNpcs != null ? new HashSet<>(unlockedNpcs) : Set.of());
        s.setKilledNpcs(killedNpcs != null ? new HashSet<>(killedNpcs) : Set.of());
        s.setFoundItems(foundItems != null ? new HashSet<>(foundItems) : Set.of());
        s.setTriggeredSanityWarnings(triggeredSanityWarnings != null ? new HashSet<>(triggeredSanityWarnings) : Set.of());
        s.setYuzuInventory(yuzuInventory != null ? new ArrayList<>(yuzuInventory) : List.of());
        s.setRevivedNpcs(revivedNpcs != null ? new HashSet<>(revivedNpcs) : Set.of());
        s.setPuzzleAttempts(puzzleAttempts != null ? new HashMap<>(puzzleAttempts) : Map.of());
        s.setNpcDialogueCounts(npcDialogueCounts != null ? new HashMap<>(npcDialogueCounts) : Map.of());
        s.setDynamicItemNames(dynamicItemNames != null ? new HashMap<>(dynamicItemNames) : Map.of());
        s.setUsedRedemptionCodes(usedRedemptionCodes != null ? new HashMap<>(usedRedemptionCodes) : Map.of());
        Map<String, List<PuzzleMemoryEntry>> pmCopy = new HashMap<>();
        if (puzzleMemory != null) {
            for (Map.Entry<String, List<PuzzleMemoryEntry>> entry : puzzleMemory.entrySet()) {
                pmCopy.put(entry.getKey(), entry.getValue() != null ? new ArrayList<>(entry.getValue()) : new ArrayList<>());
            }
        }
        s.setPuzzleMemory(pmCopy);
        s.setChatHistory(chatHistory != null ? new ArrayList<>(chatHistory) : List.of());
        s.setEnded(ended);
        s.setEndingType(endingType);
        s.setCustomLlmBaseUrl(customLlmBaseUrl);
        s.setCustomLlmApiKey(customLlmApiKey);
        s.setCustomLlmModel(customLlmModel);
        return s;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
