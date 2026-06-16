package com.yuzugame.controller;

import com.yuzugame.service.GameService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 游戏控制器 —— REST API 入口层。
 *
 * <p>提供三个 HTTP 端点，覆盖完整的游戏交互流程：
 * <ol>
 *   <li>{@code POST /api/game/new} —— 创建新游戏会话</li>
 *   <li>{@code POST /api/game/action} —— 提交玩家操作</li>
 *   <li>{@code GET /api/game/state?sessionId=xxx} —— 查询游戏状态</li>
 * </ol></p>
 *
 * <p>所有端点返回 JSON 格式的响应，包含游戏输出文本和状态快照。
 * Controller 层不包含业务逻辑，仅负责请求路由和参数提取。</p>
 */
@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameService gameService;

    /** config-llm 速率限制：每个会话 60 秒内最多 3 次 */
    private static final long CONFIG_RATE_WINDOW_MS = 60_000;
    private static final int CONFIG_RATE_MAX = 3;
    private final ConcurrentHashMap<String, RateLimitEntry> configLlmRateLimit = new ConcurrentHashMap<>();

    static final class RateLimitEntry {
        final AtomicLong count = new AtomicLong(0);
        final long windowStart = System.currentTimeMillis();
    }

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    /**
     * 创建新游戏 —— 生成会话 ID 并初始化游戏世界。
     *
     * <p>返回示例：
     * <pre>{@code
     * {
     *   "sessionId": "a1b2c3d4",
     *   "status": "opening",
     *   "output": "（导演开场旁白...）",
     *   "state": { "turn": 0, "sanity": 100, ... }
     * }
     * }</pre></p>
     *
     * @return 新游戏响应
     */
    @PostMapping("/new")
    public Map<String, Object> newGame() {
        return gameService.newGame();
    }

    /**
     * 提交玩家操作 —— 处理玩家在游戏中的每一步输入。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "sessionId": "a1b2c3d4",
     *   "message": "我看看周围有什么"
     * }
     * }</pre></p>
     *
     * <p>在 opening 阶段，任意消息都会触发柚子自我介绍并进入 playing 阶段。</p>
     *
     * @param body 请求体，含 sessionId 和 message
     * @return 游戏响应，含 output（游戏输出）和 state（状态快照）
     */
    @PostMapping("/action")
    public Map<String, Object> action(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String message = body.get("message");
        return gameService.playerMessage(sessionId, message);
    }

    /**
     * 查询游戏状态 —— 不触发任何游戏逻辑，仅返回当前状态快照。
     *
     * @param sessionId 会话 ID（URL 参数）
     * @return 含 sessionId 和 state 的响应
     */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam String sessionId) {
        return gameService.getSessionState(sessionId);
    }

    /**
     * 兑换码 —— 输入口令兑换属性奖励。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "sessionId": "a1b2c3d4",
     *   "code": "YUZU_SANITY_20"
     * }
     * }</pre></p>
     *
     * <p>兑换码不区分大小写，每个会话同一兑换码有使用次数限制。</p>
     *
     * @param body 请求体，含 sessionId 和 code
     * @return 兑换结果，含 success、message、applied（属性变化）和 state
     */
    @PostMapping("/redeem")
    public Map<String, Object> redeem(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String code = body.get("code");
        return gameService.redeemCode(sessionId, code);
    }

    @PostMapping("/config-llm")
    public Map<String, Object> configureLlm(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "message", "会话ID不能为空");
        }

        // 速率限制检查
        long now = System.currentTimeMillis();
        RateLimitEntry entry = configLlmRateLimit.compute(sessionId, (k, v) -> {
            if (v == null || now - v.windowStart > CONFIG_RATE_WINDOW_MS) {
                return new RateLimitEntry();
            }
            return v;
        });
        if (entry.count.incrementAndGet() > CONFIG_RATE_MAX) {
            return Map.of("success", false, "message", "操作过于频繁，请稍后再试");
        }

        String baseUrl = body.get("baseUrl");
        String apiKey = body.get("apiKey");
        String model = body.get("model");
        return gameService.configureLlm(sessionId, baseUrl, apiKey, model);
    }
}
