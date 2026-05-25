package com.yuzugame.service;

import com.yuzugame.engine.GameDataLoader;
import com.yuzugame.model.PromptsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InputAuditor {

    private static final Logger log = LoggerFactory.getLogger(InputAuditor.class);

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
        return dataLoader.getPrompts().getAuditor();
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
            if (result != null && result.contains("是") && !result.contains("否")) {
                log.warn("Input blocked by LLM audit, input: {}", truncateForLog(playerMessage));
                return generateWarning();
            }
        } catch (Exception e) {
            log.error("LLM audit failed, blocking input by fail-closed: {}", e.getMessage());
            return prompts().getFailClosedMessage();
        }

        return null;
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

    private String truncateForLog(String input) {
        if (input.length() <= 100) return input;
        return input.substring(0, 100) + "...";
    }
}
