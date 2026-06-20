package com.yuzugame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.engine.GameEngine;
import com.yuzugame.model.*;
import com.yuzugame.repository.GameSessionEntity;
import com.yuzugame.repository.GameSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 游戏服务 —— 会话管理与 Controller-Engine 桥接层。
 *
 * <p>GameService 是 MVC 架构中 Service 层的核心，负责：
 * <ul>
 *   <li>游戏会话的创建、存储和查找</li>
 *   <li>将 Controller 层的 HTTP 请求转化为对 GameEngine 的调用</li>
 *   <li>组装返回给前端的响应数据（输出文本 + 游戏状态快照）</li>
 * </ul></p>
 *
 * <p>存储架构 —— 二级缓存：
 * <ol>
 *   <li><b>Redis（一级缓存）</b> —— 活跃会话的快速读写，TTL 2 小时</li>
 *   <li><b>MySQL（持久存储）</b> —— 所有会话的持久化，服务重启不丢失</li>
 * </ol></p>
 *
 * <p>读写策略：
 * <ul>
 *   <li>读：Redis → MySQL → null（Cache-Aside 模式）</li>
 *   <li>写：先更新 MySQL，再删除 Redis 缓存（下次读取时回填）</li>
 *   <li>每次玩家操作后，会话同步写入 MySQL 保证持久性</li>
 * </ul></p>
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    private static final long REDIS_TTL_HOURS = 6;
    private static final String REDIS_KEY_PREFIX = "yuzu:session:";

    private final GameEngine engine;
    private final GameSessionRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GameDataLoader dataLoader;

    private final LlmService llmService;

    @Value("${yuzu.field-encryption-key:}")
    private String fieldEncryptionKey;

    public GameService(GameEngine engine, GameSessionRepository repository,
                       StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                       GameDataLoader dataLoader, LlmService llmService) {
        this.engine = engine;
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.dataLoader = dataLoader;
        this.llmService = llmService;
    }

    /**
     * 创建新游戏会话。
     *
     * <p>流程：
     * <ol>
     *   <li>生成 8 位短 UUID 作为会话 ID</li>
     *   <li>创建空的 {@link GameSession}</li>
     *   <li>调用 {@link GameEngine#initGame} 执行游戏初始化（导演开场旁白）</li>
     *   <li>写入 Redis + MySQL</li>
     * </ol></p>
     */
    public Map<String, Object> newGame() {
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        GameSession session = new GameSession();
        session.setSessionId(sessionId);

        String opening = engine.initGame(session);

        saveSession(session);

        return Map.of(
            "sessionId", sessionId,
            "status", "opening",
            "output", opening,
            "state", getState(session)
        );
    }

    /**
     * 处理玩家消息 —— 根据游戏阶段分发到不同的引擎方法。
     *
     * <p>阶段分发逻辑：
     * <ul>
     *   <li>{@code opening} 阶段 —— 调用 {@link GameEngine#processOpening} 生成柚子自我介绍</li>
     *   <li>{@code playing} 阶段 —— 调用 {@link GameEngine#processMessage} 执行完整的游戏回合</li>
     * </ul></p>
     *
     * <p>每次操作后自动持久化到 Redis + MySQL。</p>
     */
    public Map<String, Object> playerMessage(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("error", "Session ID is required");
        }
        if (message == null) {
            return Map.of("error", "Message is required");
        }
        if (message.length() > 200) {
            return Map.of("error", "消息过长，最多200字");
        }

        GameSession session = loadSession(sessionId);
        if (session == null) {
            return Map.of("error", "Session not found. Start a new game with /api/game/new");
        }

        if ("opening".equals(session.getGamePhase())) {
            String yuzuIntro = engine.processOpening(session);
            saveSession(session);
            return Map.of(
                "sessionId", sessionId,
                "output", yuzuIntro,
                "state", getState(session)
            );
        }

        String output = engine.processMessage(session, message);
        saveSession(session);

        return Map.of(
            "sessionId", sessionId,
            "output", output,
            "state", getState(session)
        );
    }

    /**
     * 查询指定会话的当前状态 —— 不触发任何游戏逻辑。
     */
    public Map<String, Object> getSessionState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("error", "Session ID is required");
        }
        GameSession session = loadSession(sessionId);
        if (session == null) {
            return Map.of("error", "Session not found");
        }
        return Map.of(
            "sessionId", sessionId,
            "state", getState(session)
        );
    }

    /**
     * 配置会话级别的 LLM 参数 —— 允许玩家使用自定义 API 地址、密钥和模型。
     *
     * <p>传入空值时清除自定义配置，恢复使用系统默认 API。</p>
     *
     * <p>配置前会调用 LLM API 进行校验，确保参数有效。</p>
     */
    public Map<String, Object> configureLlm(String sessionId, String baseUrl, String apiKey, String model) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "message", "会话ID不能为空");
        }
        GameSession session = loadSession(sessionId);
        if (session == null) {
            return Map.of("success", false, "message", "会话不存在");
        }
        if (session.isEnded()) {
            return Map.of("success", false, "message", "游戏已结束，无法修改配置");
        }

        boolean isClearing = (baseUrl == null || baseUrl.isBlank())
                          && (apiKey == null || apiKey.isBlank())
                          && (model == null || model.isBlank());
        if (!isClearing) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                try {
                    var url = new java.net.URI(baseUrl).toURL();
                    String host = url.getHost();
                    if (host == null || host.isBlank()) {
                        return Map.of("success", false, "message", "API 校验失败：URL 格式无效");
                    }
                    byte[] addr = java.net.InetAddress.getByName(host).getAddress();
                    if (addr.length == 4 && (addr[0] == 0 || addr[0] == 10
                            || (addr[0] == (byte) 172 && addr[1] >= 16 && addr[1] <= 31)
                            || (addr[0] == (byte) 192 && addr[1] == (byte) 168)
                            || (addr[0] == (byte) 127))) {
                        return Map.of("success", false, "message", "API 校验失败：不允许使用内网地址");
                    }
                    if (addr.length == 16 && addr[0] == 0 && addr[1] == 0 && addr[2] == 0
                            && (addr[3] == 0 || addr[3] == 1)) {
                        return Map.of("success", false, "message", "API 校验失败：不允许使用内网地址");
                    }
                } catch (Exception e) {
                    return Map.of("success", false, "message", "API 校验失败：URL 格式无效 - " + e.getMessage());
                }
            }
            String error = llmService.validateApi(baseUrl, apiKey, model);
            if (error != null) {
                return Map.of("success", false, "message", "API 校验失败：" + error);
            }
        }

        session.setCustomLlmBaseUrl(baseUrl);
        session.setCustomLlmApiKey(apiKey);
        session.setCustomLlmModel(model);
        saveSession(session);
        log.info("LLM config updated for session {}: model={}", sessionId, model);
        return Map.of("success", true, "message", isClearing ? "已恢复使用系统默认 API" : "LLM 配置已更新并校验通过");
    }

    public Map<String, Object> redeemCode(String sessionId, String code) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "message", "会话ID不能为空");
        }
        if (code == null || code.isBlank()) {
            return Map.of("success", false, "message", "兑换码不能为空");
        }

        GameSession session = loadSession(sessionId);
        if (session == null) {
            return Map.of("success", false, "message", "会话不存在");
        }

        if (session.isEnded()) {
            return Map.of("success", false, "message", "游戏已结束，无法使用兑换码");
        }

        RedemptionCodeConfig config = dataLoader.getRedemptionCode(code.trim());
        if (config == null) {
            return Map.of("success", false, "message", "兑换码无效");
        }

        int usedCount = session.getRedemptionCodeUseCount(config.getCode());
        if (config.getMaxUses() > 0 && usedCount >= config.getMaxUses()) {
            return Map.of("success", false, "message", "该兑换码已达到使用上限");
        }

        Map<String, Integer> rewards = config.getRewards();
        Map<String, Integer> applied = new LinkedHashMap<>();
        Player player = session.getPlayer();

        if (rewards != null) {
        for (Map.Entry<String, Integer> entry : rewards.entrySet()) {
            String attr = entry.getKey();
            int delta = entry.getValue();
            switch (attr) {
                case "sanity" -> { player.addSanity(delta); applied.put("sanity", player.getSanity()); }
                case "revelation" -> { player.addRevelation(delta); applied.put("revelation", player.getRevelation()); }
                case "affection" -> { player.addAffection(delta); applied.put("affection", player.getAffection()); }
                case "turn" -> {
                    int newTurn = Math.max(1, session.getTurn() + delta);
                    session.setTurn(newTurn);
                    applied.put("turn", newTurn);
                }
                default -> log.warn("Unknown reward attribute: {}", attr);
            }
        }
        }

        session.recordRedemptionCodeUse(config.getCode());

        String systemNarrative = "一股未知的力量从其他世界线跨域进行了干扰，你感到无比强大";
        session.addChatMessage(new GameSession.ChatMessage("SYSTEM", null, systemNarrative + "（" + config.getDescription() + "）"));

        saveSession(session);

        log.info("Redeemed code {} for session {}, rewards applied: {}", config.getCode(), sessionId, applied);

        return Map.of(
            "success", true,
            "message", "兑换成功！" + config.getDescription(),
            "output", "【系统】" + systemNarrative + "（" + config.getDescription() + "）",
            "code", config.getCode(),
            "description", config.getDescription(),
            "applied", applied,
            "state", getState(session)
        );
    }

    /**
     * 从缓存/数据库加载会话 —— Cache-Aside 模式。
     *
     * <p>查找顺序：Redis → MySQL → null</p>
     * <p>从 MySQL 加载后回填 Redis 缓存。</p>
     */
    private GameSession loadSession(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + sessionId);
            if (json != null) {
                return objectMapper.readValue(json, GameSession.class);
            }
        } catch (Exception e) {
            log.warn("Failed to load session from Redis: {}", e.getMessage());
        }

        try {
            Optional<GameSessionEntity> entity = repository.findById(sessionId);
            if (entity.isPresent()) {
                GameSession session = entity.get().toModel(resolveEncryptionKey());
                cacheToRedis(session);
                return session;
            }
        } catch (Exception e) {
            log.warn("Failed to load session from MySQL: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 保存会话 —— 双写 MySQL + Redis。
     *
     * <p>先写 MySQL 保证持久性，再写 Redis 保证缓存一致性。</p>
     */
    private void saveSession(GameSession session) {
        boolean saved = false;
        for (int attempt = 1; attempt <= 3 && !saved; attempt++) {
            try {
                GameSessionEntity entity = GameSessionEntity.fromModel(session, resolveEncryptionKey());
                repository.findById(session.getSessionId()).ifPresent(existing -> {
                    entity.setCreatedAt(existing.getCreatedAt());
                });
                repository.save(entity);
                saved = true;
            } catch (Exception e) {
                log.error("Failed to save session to MySQL (attempt {}/3): {}", attempt, e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        }

        if (saved) {
            cacheToRedis(session);
        } else {
            cacheToRedis(session);
            log.error("MySQL save failed after 3 attempts for session {}, kept Redis cache as fallback", session.getSessionId());
        }
    }

    /**
     * 将会话写入 Redis 缓存，TTL 2 小时。
     */
    private void cacheToRedis(GameSession session) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + session.getSessionId(), json, REDIS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to cache session to Redis: {}", e.getMessage());
        }
    }

    private void evictRedis(String sessionId) {
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("Failed to evict Redis cache for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 获取字段加密密钥，仅在配置了 FIELD_ENCRYPTION_KEY 时启用加密。
     */
    private String resolveEncryptionKey() {
        return (fieldEncryptionKey != null && !fieldEncryptionKey.isBlank()) ? fieldEncryptionKey : null;
    }

    private Map<String, Object> getState(GameSession session) {
        Player p = session.getPlayer();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("turn", session.getTurn());
        state.put("chapter", session.getCurrentChapter());
        state.put("mapId", session.getCurrentMapId());
        state.put("sanity", p.getSanity());
        state.put("revelation", p.getRevelation());
        state.put("affection", p.getAffection());
        state.put("inventory", new ArrayList<>(p.getInventory()));
        state.put("yuzuInventory", new ArrayList<>(session.getYuzuInventory()));
        state.put("foundItems", new ArrayList<>(session.getFoundItems()));
        state.put("solvedPuzzles", new ArrayList<>(session.getSolvedPuzzles()));
        state.put("failedPuzzles", new ArrayList<>(session.getFailedPuzzles()));
        state.put("activePuzzleId", session.getActivePuzzleId());
        state.put("exitUnlocked", session.isExitUnlocked());
        state.put("unlockedNpcs", new ArrayList<>(session.getUnlockedNpcs()));
        state.put("ended", session.isEnded());
        state.put("endingType", session.getEndingType());
        state.put("score", session.getScore());

        Map<String, Map<String, String>> presetItemMap = new LinkedHashMap<>();
        for (Map.Entry<String, ItemConfig> e : dataLoader.getAllItems().entrySet()) {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("name", e.getValue().getName());
            detail.put("type", e.getValue().getType());
            detail.put("description", e.getValue().getDescription());
            presetItemMap.put(e.getKey(), detail);
        }

        Map<String, Map<String, String>> dynamicItemMap = new LinkedHashMap<>();
        session.getDynamicItemNames().forEach((id, name) -> {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("name", name);
            detail.put("type", "DYNAMIC");
            detail.put("description", "");
            dynamicItemMap.put(id, detail);
        });

        // 构建物品名称映射：Agent 注册的名称优先，预设配置兜底
        Map<String, String> itemNames = new LinkedHashMap<>();
        for (Map.Entry<String, ItemConfig> e : dataLoader.getAllItems().entrySet()) {
            itemNames.put(e.getKey(), e.getValue().getName());
        }
        // Agent 注册的名称覆盖预设名称（Agent 命名优先）
        session.getDynamicItemNames().forEach(itemNames::put);
        state.put("itemNames", itemNames);

        List<Map<String, String>> backpack = new ArrayList<>();
        List<Map<String, String>> clues = new ArrayList<>();
        for (String itemId : p.getInventory()) {
            Map<String, String> entry = new LinkedHashMap<>();
            // Agent 注册的名称优先于预设配置
            String agentName = session.getDynamicItemName(itemId);
            if (presetItemMap.containsKey(itemId)) {
                entry.put("id", itemId);
                entry.put("name", agentName != null ? agentName : presetItemMap.get(itemId).get("name"));
                entry.put("type", presetItemMap.get(itemId).get("type"));
                entry.put("description", presetItemMap.get(itemId).get("description"));
                backpack.add(entry);
            } else if (dynamicItemMap.containsKey(itemId)) {
                entry.put("id", itemId);
                entry.put("name", agentName != null ? agentName : dynamicItemMap.get(itemId).get("name"));
                entry.put("type", dynamicItemMap.get(itemId).get("type"));
                entry.put("description", dynamicItemMap.get(itemId).get("description"));
                clues.add(entry);
            } else {
                entry.put("id", itemId);
                entry.put("name", agentName != null ? agentName : itemId);
                entry.put("type", "DYNAMIC");
                entry.put("description", "");
                clues.add(entry);
            }
        }
        state.put("backpack", backpack);
        state.put("clues", clues);

        Map<String, String> npcNames = new LinkedHashMap<>();
        Map<String, Map<String, String>> npcInfo = new LinkedHashMap<>();
        for (NpcConfig npc : dataLoader.getNpcs()) {
            npcNames.put(npc.getId(), npc.getName());
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("name", npc.getName());
            detail.put("description", npc.getDescription());
            detail.put("dialogueHint", npc.getDialogueHint());
            npcInfo.put(npc.getId(), detail);
        }
        state.put("npcNames", npcNames);
        state.put("npcInfo", npcInfo);

        Map<String, String> puzzleNames = new LinkedHashMap<>();
        for (PuzzleConfig puzzle : dataLoader.getPuzzles()) {
            puzzleNames.put(puzzle.getId(), puzzle.getName());
        }
        state.put("puzzleNames", puzzleNames);

        Map<String, String> mapNames = new LinkedHashMap<>();
        for (MapConfig map : dataLoader.getMaps()) {
            mapNames.put(map.getId(), map.getName());
        }
        state.put("mapNames", mapNames);

        Map<String, String> chapterNames = new LinkedHashMap<>();
        List<StoryConfig.ChapterDef> chapters = dataLoader.getStory() != null ? dataLoader.getStory().getChapters() : null;
        if (chapters != null) {
            for (StoryConfig.ChapterDef ch : chapters) {
                chapterNames.put(ch.getId(), ch.getName());
            }
        }
        state.put("chapterNames", chapterNames);

        Map<String, List<String>> mapNpcIds = new LinkedHashMap<>();
        for (MapConfig map : dataLoader.getMaps()) {
            mapNpcIds.put(map.getId(), map.getNpcIds() != null ? map.getNpcIds() : List.of());
        }
        state.put("mapNpcIds", mapNpcIds);

        return state;
    }
}
