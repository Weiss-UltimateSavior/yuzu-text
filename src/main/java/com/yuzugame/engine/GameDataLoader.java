package com.yuzugame.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.model.*;
import com.yuzugame.util.CodeUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

@Component
public class GameDataLoader {

    private static final Logger log = LoggerFactory.getLogger(GameDataLoader.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${yuzu.data-dir:}")
    private String dataDir;

    private volatile Map<String, ItemConfig> items = Collections.emptyMap();
    private volatile Map<String, MapConfig> maps = Collections.emptyMap();
    private volatile Map<String, NpcConfig> npcs = Collections.emptyMap();
    private volatile Map<String, PuzzleConfig> puzzles = Collections.emptyMap();
    private volatile StoryConfig story;
    private volatile ProtagonistConfig protagonist;
    private volatile List<EndingRuleConfig> endingRules = Collections.emptyList();
    private volatile Map<String, RedemptionCodeConfig> redemptionCodes = Collections.emptyMap();
    private volatile PromptsConfig prompts;
    private volatile GameConfig gameConfig;
    private volatile List<MapConfig> mapList = Collections.emptyList();
    private volatile List<NpcConfig> npcList = Collections.emptyList();

    private GameEngine gameEngine;

    static String normalizeCode(String code) {
        return CodeUtils.normalizeCode(code);
    }

    public String getDataDir() { return dataDir; }

    public void setGameEngine(GameEngine gameEngine) {
        this.gameEngine = gameEngine;
    }

    @PostConstruct
    public void init() {
        loadAllData();
    }

    public synchronized void reload() {
        log.info("Reloading game data...");
        loadAllData();
        if (gameEngine != null) {
            gameEngine.clearPatternCache();
        }
    }

    private void loadAllData() {
        Map<String, ItemConfig> newItems = new HashMap<>();
        Map<String, MapConfig> newMaps = new HashMap<>();
        Map<String, NpcConfig> newNpcs = new HashMap<>();
        Map<String, PuzzleConfig> newPuzzles = new HashMap<>();
        Map<String, RedemptionCodeConfig> newCodes = new HashMap<>();
        List<MapConfig> newMapList;
        List<NpcConfig> newNpcList;
        List<EndingRuleConfig> newEndings;
        StoryConfig newStory;
        ProtagonistConfig newProtagonist;
        PromptsConfig newPrompts;
        GameConfig newGameConfig;

        try {
            List<ItemConfig> itemList = loadJsonList("data/items.json", new TypeReference<List<ItemConfig>>() {});
            if (itemList != null) {
                for (ItemConfig i : itemList) {
                    if (i.getId() != null) newItems.put(i.getId(), i);
                }
            }

            List<MapConfig> mapsLoaded = loadJsonList("data/maps.json", new TypeReference<List<MapConfig>>() {});
            if (mapsLoaded != null) {
                for (MapConfig m : mapsLoaded) {
                    if (m.getId() != null) newMaps.put(m.getId(), m);
                }
            }
            newMapList = mapsLoaded != null ? mapsLoaded : Collections.emptyList();

            List<NpcConfig> npcsLoaded = loadJsonList("data/npcs.json", new TypeReference<List<NpcConfig>>() {});
            if (npcsLoaded != null) {
                for (NpcConfig n : npcsLoaded) {
                    if (n.getId() != null) newNpcs.put(n.getId(), n);
                }
            }
            newNpcList = npcsLoaded != null ? npcsLoaded : Collections.emptyList();

            List<PuzzleConfig> puzzleList = loadJsonList("data/puzzles.json", new TypeReference<List<PuzzleConfig>>() {});
            if (puzzleList != null) {
                for (PuzzleConfig p : puzzleList) {
                    if (p.getId() != null) newPuzzles.put(p.getId(), p);
                }
            }

            newStory = loadJson("data/story.json", StoryConfig.class);
            newProtagonist = loadJson("data/protagonist.json", ProtagonistConfig.class);

            List<EndingRuleConfig> endingsLoaded = loadJsonList("data/endings.json", new TypeReference<List<EndingRuleConfig>>() {});
            if (endingsLoaded != null) {
                endingsLoaded.sort(Comparator.comparingInt(EndingRuleConfig::getPriority));
            }
            newEndings = endingsLoaded != null ? endingsLoaded : Collections.emptyList();

            List<RedemptionCodeConfig> codesLoaded = loadJsonList("data/redemption_codes.json", new TypeReference<List<RedemptionCodeConfig>>() {});
            if (codesLoaded != null) {
                for (RedemptionCodeConfig c : codesLoaded) {
                    if (c.isActive() && c.getCode() != null) {
                        newCodes.put(normalizeCode(c.getCode()), c);
                    }
                }
            }

            newPrompts = loadJson("data/prompts.json", PromptsConfig.class);
            newGameConfig = loadJson("data/game_config.json", GameConfig.class);

            this.items = Collections.unmodifiableMap(newItems);
            this.maps = Collections.unmodifiableMap(newMaps);
            this.npcs = Collections.unmodifiableMap(newNpcs);
            this.puzzles = Collections.unmodifiableMap(newPuzzles);
            this.redemptionCodes = Collections.unmodifiableMap(newCodes);
            this.endingRules = Collections.unmodifiableList(newEndings);
            this.mapList = Collections.unmodifiableList(newMapList);
            this.npcList = Collections.unmodifiableList(newNpcList);
            this.story = newStory;
            this.protagonist = newProtagonist;
            this.prompts = newPrompts;
            this.gameConfig = newGameConfig;

            log.info("Game data loaded: {} items, {} maps, {} npcs, {} puzzles, {} endings, {} codes",
                    items.size(), maps.size(), npcs.size(), puzzles.size(), endingRules.size(), redemptionCodes.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load game data: " + e.getMessage(), e);
        }
    }

    private InputStream resolveResource(String classpathPath) throws Exception {
        String filename = classpathPath.startsWith("data/") ? classpathPath.substring(5) : classpathPath;
        if (dataDir != null && !dataDir.isEmpty()) {
            File file = new File(dataDir, filename);
            if (file.exists()) {
                return new FileInputStream(file);
            }
        }
        return new ClassPathResource(classpathPath).getInputStream();
    }

    private <T> T loadJson(String path, Class<T> clazz) throws Exception {
        try (InputStream is = resolveResource(path)) {
            return mapper.readValue(is, clazz);
        }
    }

    private <T> List<T> loadJsonList(String path, TypeReference<List<T>> typeRef) throws Exception {
        try (InputStream is = resolveResource(path)) {
            return mapper.readValue(is, typeRef);
        }
    }

    public ItemConfig getItem(String id) { return items.get(id); }
    public Map<String, ItemConfig> getAllItems() { return items; }

    public MapConfig getMap(String id) { return maps.get(id); }
    public List<MapConfig> getMaps() { return mapList; }

    public NpcConfig getNpc(String id) { return npcs.get(id); }
    public List<NpcConfig> getNpcs() { return npcList; }

    public PuzzleConfig getPuzzle(String id) { return puzzles.get(id); }
    public List<PuzzleConfig> getPuzzles() { return Collections.unmodifiableList(new ArrayList<>(puzzles.values())); }

    public StoryConfig getStory() { return story; }
    public ProtagonistConfig getProtagonist() { return protagonist; }
    public List<EndingRuleConfig> getEndingRules() { return endingRules; }

    public RedemptionCodeConfig getRedemptionCode(String code) {
        if (code == null) return null;
        return redemptionCodes.get(normalizeCode(code));
    }
    public Map<String, RedemptionCodeConfig> getAllRedemptionCodes() { return redemptionCodes; }

    public PromptsConfig getPrompts() { return prompts; }
    public GameConfig getGameConfig() { return gameConfig; }
}
