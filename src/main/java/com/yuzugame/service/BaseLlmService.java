package com.yuzugame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;

/**
 * LLM 服务基类 —— 封装与 OpenAI 兼容 API 的通用通信逻辑。
 *
 * <p>子类（LlmService / AuditLlmService）仅需指定配置路径和重试参数。</p>
 *
 * <p>配置使用 {@link AtomicReference<LlmConfig>} 保证原子更新，
 * 避免读到 baseUrl/apiKey/model 更新了一半的中间状态。</p>
 */
public abstract class BaseLlmService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** LLM 连接配置的不可变快照，通过 AtomicReference 保证原子更新 */
    public record LlmConfig(String baseUrl, String apiKey, String model) {}

    private final AtomicReference<LlmConfig> configRef = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;

    protected BaseLlmService() {
        this.client = createClient();
    }

    /** 子类指定最大重试次数 */
    protected abstract int maxRetries();

    /** 子类指定基础重试延迟（毫秒） */
    protected abstract long baseRetryDelayMs();

    /** 子类指定请求超时（秒） */
    protected int requestTimeoutSeconds() { return 90; }

    /** 子类指定 temperature */
    protected double temperature() { return 0.8; }

    /** 子类指定 max_tokens */
    protected int maxTokens() { return 2048; }

    /** 初始化或更新配置 */
    protected void setConfig(String baseUrl, String apiKey, String model) {
        configRef.set(new LlmConfig(baseUrl, apiKey, model));
    }

    /** 原子更新配置（仅更新非空字段） */
    protected void updateConfigFields(String baseUrl, String apiKey, String model) {
        LlmConfig current, updated;
        do {
            current = configRef.get();
            if (current == null) {
                updated = new LlmConfig(baseUrl, apiKey, model);
            } else {
                updated = new LlmConfig(
                    (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : current.baseUrl(),
                    (apiKey != null && !apiKey.isBlank()) ? apiKey : current.apiKey(),
                    (model != null && !model.isBlank()) ? model : current.model()
                );
            }
        } while (!configRef.compareAndSet(current, updated));
    }

    public String getBaseUrl() { LlmConfig c = configRef.get(); return c != null ? c.baseUrl() : null; }
    public String getApiKey() { LlmConfig c = configRef.get(); return c != null ? c.apiKey() : null; }
    public String getModel() { LlmConfig c = configRef.get(); return c != null ? c.model() : null; }

    /**
     * 无对话历史的 LLM 调用 —— 便捷方法。
     */
    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, null);
    }

    /**
     * 带对话历史的 LLM 调用 —— 使用默认配置。
     */
    public String chat(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        return chat(systemPrompt, userMessage, history, null, null, null);
    }

    /**
     * 完整参数的 LLM 调用 —— 支持覆盖 baseUrl/apiKey/model。
     */
    public String chat(String systemPrompt, String userMessage, List<Map<String, String>> history,
                       String overrideBaseUrl, String overrideApiKey, String overrideModel) {
        LlmConfig cfg = configRef.get();
        if (cfg == null) throw new LlmCallException("LLM 配置未初始化");

        String useBaseUrl = (overrideBaseUrl != null && !overrideBaseUrl.isBlank()) ? overrideBaseUrl : cfg.baseUrl();
        String useApiKey = (overrideApiKey != null && !overrideApiKey.isBlank()) ? overrideApiKey : cfg.apiKey();
        String useModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : cfg.model();

        String requestBody = buildRequestBody(useModel, systemPrompt, userMessage, history);
        String apiUrl = useBaseUrl.replaceAll("/+$", "") + "/chat/completions";

        for (int attempt = 1; attempt <= maxRetries(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + useApiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(requestTimeoutSeconds()))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return extractContent(response.body());
                }

                log.warn("LLM request failed with status {} (attempt {}/{}) url={}",
                        response.statusCode(), attempt, maxRetries(), useBaseUrl);

                if (attempt < maxRetries()) {
                    long delay = baseRetryDelayMs() * (1L << (attempt - 1));
                    Thread.sleep(delay);
                } else {
                    throw new LlmCallException("LLM 请求失败（HTTP " + response.statusCode() + "），已重试 " + maxRetries() + " 次");
                }

            } catch (IOException | InterruptedException e) {
                log.warn("LLM connection error: {} (attempt {}/{}) url={}",
                        e.getMessage(), attempt, maxRetries(), useBaseUrl);

                if (attempt < maxRetries()) {
                    long delay = baseRetryDelayMs() * (1L << (attempt - 1));
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } else {
                    throw new LlmCallException("LLM 连接失败：" + e.getMessage() + "，已重试 " + maxRetries() + " 次");
                }
            }
        }

        throw new LlmCallException("LLM 请求失败：超过最大重试次数");
    }

    /**
     * 校验 API 配置是否有效。
     *
     * @return null 表示有效，否则返回错误描述
     */
    public String validateApi(String testBaseUrl, String testApiKey, String testModel) {
        if (testBaseUrl == null || testBaseUrl.isBlank()) return "baseUrl 不能为空";
        if (testApiKey == null || testApiKey.isBlank()) return "apiKey 不能为空";
        if (testModel == null || testModel.isBlank()) return "model 不能为空";

        String apiUrl = testBaseUrl.replaceAll("/+$", "") + "/chat/completions";
        String requestBody = buildRequestBody(testModel, "You are a test assistant.", "Say hi in one word.", null);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + testApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) return null;
            if (response.statusCode() == 401 || response.statusCode() == 403)
                return "API 认证失败（HTTP " + response.statusCode() + "），请检查 apiKey 是否正确";
            if (response.statusCode() == 404)
                return "API 地址无效（HTTP 404），请检查 baseUrl 是否正确";
            return "API 返回错误（HTTP " + response.statusCode() + "）";
        } catch (IOException e) {
            return "无法连接到 API 地址：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "API 校验被中断";
        }
    }

    /**
     * 使用 ObjectMapper 构建 JSON 请求体，替代手动拼接。
     */
    protected String buildRequestBody(String useModel, String systemPrompt, String userMessage,
                                       List<Map<String, String>> history) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            if (history != null) {
                for (Map<String, String> h : history) {
                    String role = h.get("role");
                    if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role)) {
                        role = "user";
                    }
                    messages.add(Map.of("role", role, "content", h.get("content") != null ? h.get("content") : ""));
                }
            }

            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", useModel);
            body.put("messages", messages);
            body.put("temperature", temperature());
            body.put("max_tokens", maxTokens());

            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmCallException("构建请求体失败: " + e.getMessage());
        }
    }

    /**
     * 从 LLM API 响应中提取生成内容。
     *
     * <p>提取优先级：
     * <ol>
     *   <li>{@code choices[0].message.content} —— 标准输出</li>
     *   <li>{@code choices[0].message.reasoning_content} —— 思维链输出</li>
     * </ol></p>
     */
    protected String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode msg = root.at("/choices/0/message");
            if (msg == null || msg.isNull()) return "";

            JsonNode contentNode = msg.get("content");
            if (contentNode != null && !contentNode.isNull() && !contentNode.asText().isBlank()) {
                return contentNode.asText();
            }

            JsonNode reasoningNode = msg.get("reasoning_content");
            if (reasoningNode != null && !reasoningNode.isNull() && !reasoningNode.asText().isBlank()) {
                return reasoningNode.asText();
            }

            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static HttpClient createClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, null);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
    }

    public static class LlmCallException extends RuntimeException {
        public LlmCallException(String message) { super(message); }
    }
}
