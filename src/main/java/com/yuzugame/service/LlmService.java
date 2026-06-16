package com.yuzugame.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LLM 服务 —— 与大语言模型 API 的通信层（游戏 AI 专用）。
 *
 * <p>为所有 AI Agent（导演/柚子/地图/谜题/NPC）提供统一的 LLM 调用接口。</p>
 *
 * <p>配置项（application.yml）：
 * <ul>
 *   <li>{@code yuzu.llm.base-url} —— API 基础地址</li>
 *   <li>{@code yuzu.llm.api-key} —— 认证密钥</li>
 *   <li>{@code yuzu.llm.model} —— 模型名称</li>
 * </ul></p>
 */
@Service
public class LlmService extends BaseLlmService {

    private static final int MAX_RETRIES = 5;
    private static final long BASE_RETRY_DELAY_MS = 3000;

    @Value("${yuzu.llm.base-url:}")
    private String baseUrlValue;

    @Value("${yuzu.llm.api-key:}")
    private String apiKeyValue;

    @Value("${yuzu.llm.model:}")
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
    protected int requestTimeoutSeconds() { return 90; }

    @Override
    protected double temperature() { return 0.8; }

    @Override
    protected int maxTokens() { return 2048; }

    public void updateConfig(String baseUrl, String apiKey, String model) {
        updateConfigFields(baseUrl, apiKey, model);
        log.info("LlmService config updated: baseUrl={}, model={}", getBaseUrl(), getModel());
    }
}
