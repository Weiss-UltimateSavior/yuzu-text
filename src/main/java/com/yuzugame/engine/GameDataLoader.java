package com.yuzugame.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.model.*;
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

    private Map<String, ItemConfig> items = new HashMap<>();
    private Map<String, MapConfig> maps = new HashMap<>();
    private Map<String, NpcConfig> npcs = new HashMap<>();
    private Map<String, PuzzleConfig> puzzles = new HashMap<>();
    private StoryConfig story;
    private ProtagonistConfig protagonist;
    private List<EndingRuleConfig> endingRules = new ArrayList<>();
    private Map<String, RedemptionCodeConfig> redemptionCodes = new HashMap<>();
    private PromptsConfig prompts;
    private GameConfig gameConfig;
    private List<MapConfig> mapList = new ArrayList<>();
    private List<NpcConfig> npcList = new ArrayList<>();

    static String normalizeCode(String code) {
        if (code == null) return null;
        StringBuilder sb = new StringBuilder(code.length());
        for (char c : code.toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append((char) (c - 32));
            else sb.append(c);
        }
        return sb.toString();
    }

    public String getDataDir() { return dataDir; }

    @PostConstruct
    public void init() {
        try {
            List<ItemConfig> itemList = loadJsonList("data/items.json", new TypeReference<List<ItemConfig>>() {});
            items = new HashMap<>();
            for (ItemConfig i : itemList) items.put(i.getId(), i);

            List<MapConfig> mapsLoaded = loadJsonList("data/maps.json", new TypeReference<List<MapConfig>>() {});
            maps = new HashMap<>();
            for (MapConfig m : mapsLoaded) maps.put(m.getId(), m);
            this.mapList = mapsLoaded;

            List<NpcConfig> npcsLoaded = loadJsonList("data/npcs.json", new TypeReference<List<NpcConfig>>() {});
            npcs = new HashMap<>();
            for (NpcConfig n : npcsLoaded) npcs.put(n.getId(), n);
            this.npcList = npcsLoaded;

            List<PuzzleConfig> puzzleList = loadJsonList("data/puzzles.json", new TypeReference<List<PuzzleConfig>>() {});
            puzzles = new HashMap<>();
            for (PuzzleConfig p : puzzleList) puzzles.put(p.getId(), p);

            story = loadJson("data/story.json", StoryConfig.class);
            protagonist = loadJson("data/protagonist.json", ProtagonistConfig.class);

            List<EndingRuleConfig> endingsLoaded = loadJsonList("data/endings.json", new TypeReference<List<EndingRuleConfig>>() {});
            endingsLoaded.sort(java.util.Comparator.comparingInt(EndingRuleConfig::getPriority));
            this.endingRules = endingsLoaded;

            List<RedemptionCodeConfig> codesLoaded = loadJsonList("data/redemption_codes.json", new TypeReference<List<RedemptionCodeConfig>>() {});
            redemptionCodes = new HashMap<>();
            for (RedemptionCodeConfig c : codesLoaded) {
                if (c.isActive()) {
                    redemptionCodes.put(normalizeCode(c.getCode()), c);
                }
            }

            prompts = loadJson("data/prompts.json", PromptsConfig.class);
            gameConfig = loadJson("data/game_config.json", GameConfig.class);

            log.info("Game data loaded: {} items, {} maps, {} npcs, {} puzzles, {} endings, {} codes",
                    items.size(), maps.size(), npcs.size(), puzzles.size(), endingRules.size(), redemptionCodes.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load game data: " + e.getMessage(), e);
        }
    }

    public synchronized void reload() {
        log.info("Reloading game data...");
        init();
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

    public RedemptionCodeConfig getRedemptionCode(String code) { return redemptionCodes.get(normalizeCode(code)); }
    public Map<String, RedemptionCodeConfig> getAllRedemptionCodes() { return redemptionCodes; }

    public PromptsConfig getPrompts() { return prompts; }
    public GameConfig getGameConfig() { return gameConfig; }
}
