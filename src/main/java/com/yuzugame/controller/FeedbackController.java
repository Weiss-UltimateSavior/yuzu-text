package com.yuzugame.controller;

import com.yuzugame.model.Feedback;
import com.yuzugame.repository.FeedbackRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);
    private static final int MAX_SUBMISSIONS_PER_IP = 5;
    private static final long RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

    private final FeedbackRepository feedbackRepository;
    private final ConcurrentHashMap<String, RateLimitEntry> rateLimitMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler;

    private static class RateLimitEntry {
        final long windowStart;
        final AtomicInteger count;
        RateLimitEntry(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(1);
        }
    }

    public FeedbackController(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feedback-ratelimit-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredEntries,
                RATE_LIMIT_WINDOW_MS, RATE_LIMIT_WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        cleanupScheduler.shutdown();
    }

    private void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        rateLimitMap.entrySet().removeIf(e -> now - e.getValue().windowStart > RATE_LIMIT_WINDOW_MS);
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        RateLimitEntry entry = rateLimitMap.get(ip);
        if (entry == null || now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
            rateLimitMap.put(ip, new RateLimitEntry(now));
            return false;
        }
        return entry.count.incrementAndGet() > MAX_SUBMISSIONS_PER_IP;
    }

    /**
     * 解析客户端真实 IP。
     * 优先使用 X-Forwarded-For 的最后一个可信代理之前的 IP，
     * 但仅当请求来自可信代理时才信任该头，否则使用 remoteAddr。
     */
    private String resolveClientIp(String forwardedFor, jakarta.servlet.http.HttpServletRequest request) {
        // 如果存在反向代理，X-Forwarded-For 最右边的不可伪造条目是代理添加的
        // 简化策略：如果 X-Forwarded-For 存在，取第一个（最原始客户端），
        // 但需验证 remoteAddr 是本机/内网代理（生产环境应配置可信代理列表）
        String remoteAddr = request.getRemoteAddr();
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // 仅当请求来自本机或内网时信任 X-Forwarded-For
            if (isTrustedProxy(remoteAddr)) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String addr) {
        return "127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr)
                || addr.startsWith("10.") || addr.startsWith("192.168.")
                || addr.startsWith("172.16.") || addr.startsWith("172.17.")
                || addr.startsWith("172.18.") || addr.startsWith("172.19.")
                || addr.startsWith("172.2") || addr.startsWith("172.3");
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody Map<String, String> body,
                                      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                      jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveClientIp(forwardedFor, request);
        if (isRateLimited(ip)) {
            return Map.of("ok", false, "error", "提交过于频繁，请稍后再试");
        }

        String contact = body.getOrDefault("contact", "").trim();
        String content = body.getOrDefault("content", "").trim();
        String sessionId = body.getOrDefault("sessionId", "").trim();

        if (content.isEmpty()) {
            return Map.of("ok", false, "error", "建议内容不能为空"); // 内容必填
        }
        if (content.length() > 2000) {
            return Map.of("ok", false, "error", "建议内容不能超过2000字"); // 防止超长输入
        }
        if (contact.length() > 128) {
            return Map.of("ok", false, "error", "联系方式过长"); // 联系方式长度限制
        }

        Feedback feedback = new Feedback();
        feedback.setContact(contact);
        feedback.setContent(content);
        if (!sessionId.isEmpty()) {
            feedback.setSessionId(sessionId); // 关联游戏会话（可选）
        }

        try {
            feedbackRepository.save(feedback);
            log.info("Feedback saved: id={}, sessionId={}", feedback.getId(), sessionId);
            return Map.of("ok", true, "message", "感谢你的反馈！");
        } catch (Exception e) {
            log.error("Failed to save feedback: {}", e.getMessage()); // 持久化失败兜底
            return Map.of("ok", false, "error", "提交失败，请稍后再试");
        }
    }
}
