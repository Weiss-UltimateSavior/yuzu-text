package com.yuzugame.service;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.PromptsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InputAuditor {

    private static final Logger log = LoggerFactory.getLogger(InputAuditor.class);
    /** ObjectMapper 线程安全且初始化开销大，应全局重用 */
    private static final ObjectMapper OM = new ObjectMapper();

    private final AuditLlmService auditLlmService;
    private final GameDataLoader dataLoader;

    private volatile boolean enabled = true;

    private int warningIndex = 0;

    public InputAuditor(AuditLlmService auditLlmService, GameDataLoader dataLoader) {
        this.auditLlmService = auditLlmService;
        this.dataLoader = dataLoader;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) {
        boolean old = this.enabled;
        this.enabled = v;
        if (old != v) {
            log.info("InputAuditor enabled: {} -> {}", old, v);
        }
    }

    private PromptsConfig.AuditorPrompts prompts() {
        PromptsConfig promptsConfig = dataLoader.getPrompts();
        if (promptsConfig == null || promptsConfig.getAuditor() == null) {
            return new PromptsConfig.AuditorPrompts();
        }
        return promptsConfig.getAuditor();
    }

    public String audit(String playerMessage) {
        if (!enabled) {
            return null;
        }

        if (playerMessage == null || playerMessage.isBlank()) {
            return null;
        }

        try {
            String systemPrompt = prompts().getSystemPrompt();
            String result = auditLlmService.chat(systemPrompt, "玩家输入：" + playerMessage);
            if (result != null) {
                String trimmed = result.trim();
                boolean blocked = isBlocked(trimmed);
                if (blocked) {
                    // 日志中仅记录输入长度和哈希，避免泄露玩家隐私内容
                    log.warn("Input blocked by LLM audit, length={}, hash={}",
                            playerMessage.length(), hashForLog(playerMessage));
                    return generateWarning();
                }
            }
        } catch (Exception e) {
            log.error("LLM audit failed, blocking input by fail-closed: {}", e.getMessage());
            return prompts().getFailClosedMessage();
        }

        return null;
    }

    /**
     * 解析 LLM 审核结果，判断是否拦截。
     *
     * <p>优先解析结构化 JSON 输出（{"blocked": true/false}），
     * 若非 JSON 则回退到首行关键词匹配，要求 LLM 在首行明确输出 BLOCK 或 PASS。</p>
     */
    private boolean isBlocked(String result) {
        if (result == null || result.isBlank()) return false;
        String trimmed = result.trim();

        // 优先尝试 JSON 解析
        if (trimmed.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = OM.readTree(trimmed);
                if (node.has("blocked")) {
                    return node.get("blocked").asBoolean(false);
                }
                if (node.has("block")) {
                    return node.get("block").asBoolean(false);
                }
            } catch (Exception ignored) {
                // 非 JSON 或解析失败，回退到关键词匹配
            }
        }

        // 回退：检查首行是否为明确的 BLOCK 标识
        String firstLine = trimmed.split("\\r?\\n", 2)[0].trim().toUpperCase();
        // 严格匹配 BLOCK 或 BLOCKED，避免误匹配 UNBLOCK
        if (firstLine.equals("BLOCK") || firstLine.equals("BLOCKED")
                || firstLine.equals("REJECT") || firstLine.equals("REJECTED")
                || firstLine.equals("拒绝") || firstLine.equals("拦截")) {
            return true;
        }
        // 首行以 BLOCK: 或 REJECT: 开头
        if (firstLine.startsWith("BLOCK:") || firstLine.startsWith("REJECT:")) {
            return true;
        }
        return false;
    }

    private synchronized String generateWarning() {
        List<String> narratives = prompts().getWarningNarratives();
        if (narratives == null || narratives.isEmpty()) {
            return "【系统警告】一阵不和谐的嗡鸣在你耳边响起。";
        }
        String warning = narratives.get(warningIndex % narratives.size());
        warningIndex++;
        return "【系统警告】" + warning;
    }

    /** 计算输入的 SHA-256 哈希前 8 位，用于日志追踪而不泄露内容 */
    private String hashForLog(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
