package com.yuzugame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.engine.GameEngine;
import com.yuzugame.model.*;
import com.yuzugame.repository.GameSessionEntity;
import com.yuzugame.repository.GameSessionRepository;
import jakarta.annotation.PreDestroy;
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
 *   <li><b>Redis（一级缓存）</b> —— 活跃会话的快速读写，TTL {@value #REDIS_TTL_HOURS} 小时</li>
 *   <li><b>MySQL（持久存储）</b> —— 所有会话的持久化，服务重启不丢失</li>
 * </ol></p>
 *
 * <p>读写策略：
 * <ul>
 *   <li>读：Redis → MySQL → null（Cache-Aside 模式）</li>
 *   <li>写：先写 Redis 缓存保证可用性，再写 MySQL 持久化；MySQL 失败时异步重试</li>
 *   <li>每次玩家操作后，会话同步写入 MySQL 保证持久性</li>
 * </ul></p>
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    private static final long REDIS_TTL_HOURS = 6;
    private static final String REDIS_KEY_PREFIX = "yuzu:session:";
    /** 玩家单条消息最大长度 */
    private static final int MAX_PLAYER_MESSAGE_LENGTH = 200;

    /** MySQL 写入失败时的异步重试线程池 */
    private final java.util.concurrent.ExecutorService retryExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "session-save-retry");
                t.setDaemon(true);
                return t;
            });

    private final GameEngine engine;
    private final GameSessionRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GameDataLoader dataLoader;

    private final LlmService llmService;

    @Value("${yuzu.field-encryption-key:}")
    private String fieldEncryptionKey;

    @PreDestroy
    void shutdown() {
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Retry executor did not terminate in 5s, forcing shutdown");
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

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
        if (message.length() > MAX_PLAYER_MESSAGE_LENGTH) {
            return Map.of("error", "消息过长，最多" + MAX_PLAYER_MESSAGE_LENGTH + "字");
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
                    String validationError = validateLlmHost(host);
                    if (validationError != null) {
                        return Map.of("success", false, "message", validationError);
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

    /**
     * 校验 LLM API 主机是否安全（防 SSRF）。
     *
     * <p>检查所有解析到的 IP 地址，拒绝内网/回环/链路本地等地址。
     * 注意：DNS 重绑定攻击仍可能在校验后变更解析结果，
     * 生产环境应在 HttpClient 层固定解析的 IP。</p>
     *
     * @param host 主机名
     * @return 错误消息，null 表示通过
     */
    private String validateLlmHost(String host) {
        try {
            java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : all) {
                if (isPrivateOrLoopback(addr)) {
                    return "API 校验失败：不允许使用内网地址 (" + addr.getHostAddress() + ")";
                }
            }
            return null;
        } catch (java.net.UnknownHostException e) {
            return "API 校验失败：无法解析主机名 - " + e.getMessage();
        }
    }

    /** 判断地址是否为内网/回环/链路本地/多播等不可访问外网地址 */
    private boolean isPrivateOrLoopback(java.net.InetAddress addr) {
        // isLoopbackAddress() 覆盖 IPv4 127.0.0.0/8 和 IPv6 ::1
        // isSiteLocalAddress() 覆盖 IPv4 RFC 1918 私有地址（10/8, 172.16/12, 192.168/16）
        // isLinkLocalAddress() 覆盖 IPv4 169.254/16 和 IPv6 fe80::/10
        // isAnyLocalAddress() 覆盖 0.0.0.0 和 ::
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7（isSiteLocalAddress 不覆盖 ULA）
        if (addr instanceof java.net.Inet6Address v6) {
            byte[] b = v6.getAddress();
            if (b.length == 16) {
                int firstByte = b[0] & 0xff;
                // fc00::/7 -> 首字节 0xfc 或 0xfd
                if (firstByte == 0xfc || firstByte == 0xfd) return true;
            }
        }
        // IPv4 0.0.0.0/8（isAnyLocalAddress 仅匹配 0.0.0.0 本身）
        if (addr instanceof java.net.Inet4Address v4) {
            byte[] b = v4.getAddress();
            if (b.length == 4 && b[0] == 0) return true;
        }
        return false;
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
     * 保存会话 —— 先写 Redis 缓存（快速、保证可用性），再异步重试写 MySQL。
     *
     * <p>策略调整说明：
     * <ul>
     *   <li>先写 Redis：保证后续读取可用，避免阻塞请求线程</li>
     *   <li>MySQL 写入失败时记录到异步重试队列，由后台线程重试</li>
     *   <li>避免请求线程被重试 sleep 阻塞，防止 Tomcat 线程池耗尽</li>
     * </ul></p>
     */
    private void saveSession(GameSession session) {
        // 先写 Redis 缓存，保证后续读取可用
        cacheToRedis(session);
        // 同步尝试一次 MySQL 写入（快速失败）
        try {
            GameSessionEntity entity = GameSessionEntity.fromModel(session, resolveEncryptionKey());
            repository.findById(session.getSessionId()).ifPresent(existing -> {
                entity.setCreatedAt(existing.getCreatedAt());
            });
            repository.save(entity);
        } catch (Exception e) {
            log.error("MySQL save failed for session {}, queued for async retry: {}",
                    session.getSessionId(), e.getMessage());
            // 异步重试，不阻塞请求线程
            asyncRetrySave(session);
        }
    }

    /** 异步重试保存到 MySQL，最多 3 次，间隔递增 */
    private void asyncRetrySave(GameSession session) {
        retryExecutor.submit(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    GameSessionEntity entity = GameSessionEntity.fromModel(session, resolveEncryptionKey());
                    repository.findById(session.getSessionId()).ifPresent(existing -> {
                        entity.setCreatedAt(existing.getCreatedAt());
                    });
                    repository.save(entity);
                    log.info("Async retry save succeeded for session {} on attempt {}",
                            session.getSessionId(), attempt);
                    return;
                } catch (Exception e) {
                    log.warn("Async retry save attempt {}/3 failed for session {}: {}",
                            attempt, session.getSessionId(), e.getMessage());
                }
            }
            log.error("All async retry attempts failed for session {}", session.getSessionId());
        });
    }

    /**
     * 将会话写入 Redis 缓存，TTL {@value #REDIS_TTL_HOURS} 小时。
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
