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

    /** 配置版本号，每次 reload 自增，用于失效 GameEngine 的模式缓存 */
    private volatile int configVersion = 0;

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
        configVersion++;
        if (gameEngine != null) {
            gameEngine.clearPatternCache();
        }
    }

    /** 获取当前配置版本号，用于检测配置是否已重载 */
    public int getConfigVersion() {
        return configVersion;
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

        // 各文件独立加载，单文件失败不影响其他文件
        List<String> errors = new java.util.ArrayList<>();

        // items.json
        try {
            List<ItemConfig> itemList = loadJsonList("data/items.json", new TypeReference<List<ItemConfig>>() {});
            if (itemList != null) {
                for (ItemConfig i : itemList) {
                    if (i.getId() != null) newItems.put(i.getId(), i);
                }
            }
        } catch (Exception e) {
            errors.add("items.json: " + e.getMessage());
            log.warn("Failed to load items.json, keeping old config: {}", e.getMessage());
            newItems = new HashMap<>(this.items);
        }

        // maps.json
        try {
            List<MapConfig> mapsLoaded = loadJsonList("data/maps.json", new TypeReference<List<MapConfig>>() {});
            if (mapsLoaded != null) {
                for (MapConfig m : mapsLoaded) {
                    if (m.getId() != null) newMaps.put(m.getId(), m);
                }
            }
            newMapList = mapsLoaded != null ? mapsLoaded : Collections.emptyList();
        } catch (Exception e) {
            errors.add("maps.json: " + e.getMessage());
            log.warn("Failed to load maps.json, keeping old config: {}", e.getMessage());
            newMaps = new HashMap<>(this.maps);
            newMapList = this.mapList;
        }

        // npcs.json
        try {
            List<NpcConfig> npcsLoaded = loadJsonList("data/npcs.json", new TypeReference<List<NpcConfig>>() {});
            if (npcsLoaded != null) {
                for (NpcConfig n : npcsLoaded) {
                    if (n.getId() != null) newNpcs.put(n.getId(), n);
                }
            }
            newNpcList = npcsLoaded != null ? npcsLoaded : Collections.emptyList();
        } catch (Exception e) {
            errors.add("npcs.json: " + e.getMessage());
            log.warn("Failed to load npcs.json, keeping old config: {}", e.getMessage());
            newNpcs = new HashMap<>(this.npcs);
            newNpcList = this.npcList;
        }

        // puzzles.json
        try {
            List<PuzzleConfig> puzzleList = loadJsonList("data/puzzles.json", new TypeReference<List<PuzzleConfig>>() {});
            if (puzzleList != null) {
                for (PuzzleConfig p : puzzleList) {
                    if (p.getId() != null) newPuzzles.put(p.getId(), p);
                }
            }
        } catch (Exception e) {
            errors.add("puzzles.json: " + e.getMessage());
            log.warn("Failed to load puzzles.json, keeping old config: {}", e.getMessage());
            newPuzzles = new HashMap<>(this.puzzles);
        }

        // story.json
        try {
            newStory = loadJson("data/story.json", StoryConfig.class);
        } catch (Exception e) {
            errors.add("story.json: " + e.getMessage());
            log.warn("Failed to load story.json, keeping old config: {}", e.getMessage());
            newStory = this.story;
        }

        // protagonist.json
        try {
            newProtagonist = loadJson("data/protagonist.json", ProtagonistConfig.class);
        } catch (Exception e) {
            errors.add("protagonist.json: " + e.getMessage());
            log.warn("Failed to load protagonist.json, keeping old config: {}", e.getMessage());
            newProtagonist = this.protagonist;
        }

        // endings.json
        try {
            List<EndingRuleConfig> endingsLoaded = loadJsonList("data/endings.json", new TypeReference<List<EndingRuleConfig>>() {});
            if (endingsLoaded != null) {
                endingsLoaded.sort(Comparator.comparingInt(EndingRuleConfig::getPriority));
            }
            newEndings = endingsLoaded != null ? endingsLoaded : Collections.emptyList();
        } catch (Exception e) {
            errors.add("endings.json: " + e.getMessage());
            log.warn("Failed to load endings.json, keeping old config: {}", e.getMessage());
            newEndings = this.endingRules;
        }

        // redemption_codes.json
        try {
            List<RedemptionCodeConfig> codesLoaded = loadJsonList("data/redemption_codes.json", new TypeReference<List<RedemptionCodeConfig>>() {});
            if (codesLoaded != null) {
                for (RedemptionCodeConfig c : codesLoaded) {
                    if (c.isActive() && c.getCode() != null) {
                        newCodes.put(normalizeCode(c.getCode()), c);
                    }
                }
            }
        } catch (Exception e) {
            errors.add("redemption_codes.json: " + e.getMessage());
            log.warn("Failed to load redemption_codes.json, keeping old config: {}", e.getMessage());
            newCodes = new HashMap<>(this.redemptionCodes);
        }

        // prompts.json
        try {
            newPrompts = loadJson("data/prompts.json", PromptsConfig.class);
        } catch (Exception e) {
            errors.add("prompts.json: " + e.getMessage());
            log.warn("Failed to load prompts.json, keeping old config: {}", e.getMessage());
            newPrompts = this.prompts;
        }

        // game_config.json
        try {
            newGameConfig = loadJson("data/game_config.json", GameConfig.class);
        } catch (Exception e) {
            errors.add("game_config.json: " + e.getMessage());
            log.warn("Failed to load game_config.json, keeping old config: {}", e.getMessage());
            newGameConfig = this.gameConfig;
        }

        // 首次加载（旧配置为空）时，若关键配置缺失则抛出异常阻止启动
        if (newStory == null) {
            throw new RuntimeException("Critical config story.json failed to load and no previous config available");
        }
        if (newGameConfig == null) {
            throw new RuntimeException("Critical config game_config.json failed to load and no previous config available");
        }

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

        log.info("Game data loaded: {} items, {} maps, {} npcs, {} puzzles, {} endings, {} codes{}",
                items.size(), maps.size(), npcs.size(), puzzles.size(), endingRules.size(), redemptionCodes.size(),
                errors.isEmpty() ? "" : " (with " + errors.size() + " errors: " + String.join("; ", errors) + ")");
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
