package com.yuzugame.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * 游戏数据加载器 —— 在应用启动时从 JSON 配置文件加载所有游戏数据。
 *
 * <p>加载的数据包括：
 * <ul>
 *   <li>{@code data/items.json} → 物品配置（ItemConfig）</li>
 *   <li>{@code data/maps.json} → 地图配置（MapConfig）</li>
 *   <li>{@code data/npcs.json} → NPC配置（NpcConfig）</li>
 *   <li>{@code data/puzzles.json} → 谜题配置（PuzzleConfig）</li>
 *   <li>{@code data/story.json} → 故事/章节/结局配置（StoryConfig）</li>
 *   <li>{@code data/protagonist.json} → 主角柚子配置（ProtagonistConfig）</li>
 * </ul></p>
 *
 * <p>数据在 {@link PostConstruct} 阶段一次性加载到内存中，运行期间不再重新读取。
 * 提供 ID 查找和列表获取两种访问方式。</p>
 */
@Component
public class GameDataLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 物品配置映射：itemId → ItemConfig */
    private Map<String, ItemConfig> items = new HashMap<>();

    /** 地图配置映射：mapId → MapConfig */
    private Map<String, MapConfig> maps = new HashMap<>();

    /** NPC配置映射：npcId → NpcConfig */
    private Map<String, NpcConfig> npcs = new HashMap<>();

    /** 谜题配置映射：puzzleId → PuzzleConfig */
    private Map<String, PuzzleConfig> puzzles = new HashMap<>();

    /** 故事配置（全局唯一） */
    private StoryConfig story;

    /** 主角柚子配置（全局唯一） */
    private ProtagonistConfig protagonist;

    /** 结局规则配置列表（按 priority 排序） */
    private List<EndingRuleConfig> endingRules = new ArrayList<>();

    /** 兑换码配置映射：code(归一化) → RedemptionCodeConfig */
    private Map<String, RedemptionCodeConfig> redemptionCodes = new HashMap<>();

    /** 提示词配置（全局唯一） */
    private PromptsConfig prompts;

    /** 游戏配置（全局唯一） */
    private GameConfig gameConfig;

    static String normalizeCode(String code) {
        if (code == null) return null;
        StringBuilder sb = new StringBuilder(code.length());
        for (char c : code.toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append((char) (c - 32));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 地图配置列表（保持 JSON 中的原始顺序） */
    private List<MapConfig> mapList = new ArrayList<>();

    /** NPC配置列表（保持 JSON 中的原始顺序） */
    private List<NpcConfig> npcList = new ArrayList<>();

    /**
     * 应用启动后自动执行 —— 加载所有 JSON 数据文件。
     *
     * <p>如果任一文件加载失败，将抛出 RuntimeException 阻止应用启动。</p>
     */
    @PostConstruct
    public void init() {
        try {
            List<ItemConfig> itemList = loadJsonList("data/items.json", new TypeReference<List<ItemConfig>>() {});
            for (ItemConfig i : itemList) items.put(i.getId(), i);

            List<MapConfig> mapsLoaded = loadJsonList("data/maps.json", new TypeReference<List<MapConfig>>() {});
            for (MapConfig m : mapsLoaded) maps.put(m.getId(), m);
            this.mapList = mapsLoaded;

            List<NpcConfig> npcsLoaded = loadJsonList("data/npcs.json", new TypeReference<List<NpcConfig>>() {});
            for (NpcConfig n : npcsLoaded) npcs.put(n.getId(), n);
            this.npcList = npcsLoaded;

            List<PuzzleConfig> puzzleList = loadJsonList("data/puzzles.json", new TypeReference<List<PuzzleConfig>>() {});
            for (PuzzleConfig p : puzzleList) puzzles.put(p.getId(), p);

            story = loadJson("data/story.json", StoryConfig.class);
            protagonist = loadJson("data/protagonist.json", ProtagonistConfig.class);

            List<EndingRuleConfig> endingsLoaded = loadJsonList("data/endings.json", new TypeReference<List<EndingRuleConfig>>() {});
            endingsLoaded.sort(java.util.Comparator.comparingInt(EndingRuleConfig::getPriority));
            this.endingRules = endingsLoaded;

            List<RedemptionCodeConfig> codesLoaded = loadJsonList("data/redemption_codes.json", new TypeReference<List<RedemptionCodeConfig>>() {});
            for (RedemptionCodeConfig c : codesLoaded) {
                if (c.isActive()) {
                    redemptionCodes.put(normalizeCode(c.getCode()), c);
                }
            }

            prompts = loadJson("data/prompts.json", PromptsConfig.class);
            gameConfig = loadJson("data/game_config.json", GameConfig.class);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load game data: " + e.getMessage(), e);
        }
    }

    /** 从 classpath 加载单个 JSON 对象 */
    private <T> T loadJson(String path, Class<T> clazz) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return mapper.readValue(is, clazz);
        }
    }

    /** 从 classpath 加载 JSON 数组 */
    private <T> List<T> loadJsonList(String path, TypeReference<List<T>> typeRef) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return mapper.readValue(is, typeRef);
        }
    }

    // ---- ID 查找方法 ----

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
