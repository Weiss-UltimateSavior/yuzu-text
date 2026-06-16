package com.yuzugame.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 审核 LLM 服务 —— 专用于输入安全审核的独立 LLM 通信层。
 *
 * <p>与 {@link LlmService} 完全隔离，使用独立的 API 地址、密钥和模型，
 * 确保审核服务与游戏 AI 服务互不影响。</p>
 *
 * <p>配置项（application.yml）：
 * <ul>
 *   <li>{@code yuzu.audit-llm.base-url} —— 审核 API 基础地址</li>
 *   <li>{@code yuzu.audit-llm.api-key} —— 审核认证密钥</li>
 *   <li>{@code yuzu.audit-llm.model} —— 审核模型名称</li>
 * </ul></p>
 */
@Service
public class AuditLlmService extends BaseLlmService {

    private static final int MAX_RETRIES = 2;
    private static final long BASE_RETRY_DELAY_MS = 1000;

    @Value("${yuzu.audit-llm.base-url:${yuzu.llm.base-url:}}")
    private String baseUrlValue;

    @Value("${yuzu.audit-llm.api-key:${yuzu.llm.api-key:}}")
    private String apiKeyValue;

    @Value("${yuzu.audit-llm.model:${yuzu.llm.model:}}")
    private String modelValue;

    @PostConstruct
    void init() {
        setConfig(baseUrlValue, apiKeyValue, modelValue);
    }

    @Override
    protected int maxRetries() { return MAX_RETRIES; }

    @Override
    protected long baseRetryDelayMs() { return BASE_RETRY_DELAY_MS; }

    @Override
    protected int requestTimeoutSeconds() { return 45; }

    @Override
    protected double temperature() { return 1.0; }

    @Override
    protected int maxTokens() { return 512; }

    public void updateConfig(String baseUrl, String apiKey, String model) {
        updateConfigFields(baseUrl, apiKey, model);
        log.info("AuditLlmService config updated: baseUrl={}, model={}", getBaseUrl(), getModel());
    }
}
