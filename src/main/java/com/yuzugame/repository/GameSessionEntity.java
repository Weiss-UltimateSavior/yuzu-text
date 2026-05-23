package com.yuzugame.repository;

import com.yuzugame.model.GameSession;
import com.yuzugame.model.Player;
import com.yuzugame.model.PuzzleMemoryEntry;
import com.yuzugame.repository.JsonConverters.*;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "game_sessions")
public class GameSessionEntity {

    @Id
    @Column(length = 16)
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
        e.solvedPuzzles = session.getSolvedPuzzles();
        e.failedPuzzles = session.getFailedPuzzles();
        e.unlockedNpcs = session.getUnlockedNpcs();
        e.killedNpcs = session.getKilledNpcs();
        e.foundItems = session.getFoundItems();
        e.triggeredSanityWarnings = session.getTriggeredSanityWarnings();
        e.yuzuInventory = session.getYuzuInventory();
        e.revivedNpcs = session.getRevivedNpcs();
        e.puzzleAttempts = session.getPuzzleAttempts();
        e.npcDialogueCounts = session.getNpcDialogueCounts();
        e.dynamicItemNames = session.getDynamicItemNames();
        e.usedRedemptionCodes = session.getUsedRedemptionCodes();
        e.puzzleMemory = session.getPuzzleMemory();
        e.chatHistory = session.getChatHistory();
        e.ended = session.isEnded();
        e.endingType = session.getEndingType();
        e.updatedAt = Instant.now();
        if (e.createdAt == null) e.createdAt = Instant.now();
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
        s.getSolvedPuzzles().addAll(solvedPuzzles != null ? solvedPuzzles : Set.of());
        s.getFailedPuzzles().addAll(failedPuzzles != null ? failedPuzzles : Set.of());
        s.getUnlockedNpcs().addAll(unlockedNpcs != null ? unlockedNpcs : Set.of());
        s.getKilledNpcs().addAll(killedNpcs != null ? killedNpcs : Set.of());
        s.getFoundItems().addAll(foundItems != null ? foundItems : Set.of());
        s.getTriggeredSanityWarnings().addAll(triggeredSanityWarnings != null ? triggeredSanityWarnings : Set.of());
        s.getYuzuInventory().addAll(yuzuInventory != null ? yuzuInventory : List.of());
        s.getRevivedNpcs().addAll(revivedNpcs != null ? revivedNpcs : Set.of());
        if (puzzleAttempts != null) s.getPuzzleAttempts().putAll(puzzleAttempts);
        if (npcDialogueCounts != null) s.getNpcDialogueCounts().putAll(npcDialogueCounts);
        if (dynamicItemNames != null) s.getDynamicItemNames().putAll(dynamicItemNames);
        if (usedRedemptionCodes != null) s.getUsedRedemptionCodes().putAll(usedRedemptionCodes);
        if (puzzleMemory != null) s.getPuzzleMemory().putAll(puzzleMemory);
        if (chatHistory != null) s.getChatHistory().addAll(chatHistory);
        s.setEnded(ended);
        s.setEndingType(endingType);
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
    public Instant getUpdatedAt() { return updatedAt; }
}
