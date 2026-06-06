package com.yuzugame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzugame.util.CodeUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;

/**
 * LLM 服务 —— 与大语言模型 API 的通信层。
 *
 * <p>本类封装了与 DeepSeek API（OpenAI 兼容格式）的 HTTP 通信，
 * 为所有 AI Agent 提供统一的 LLM 调用接口。</p>
 *
 * <p>核心功能：
 * <ul>
 *   <li>构建 OpenAI 兼容的 Chat Completion 请求（system + history + user）</li>
 *   <li>支持多轮对话历史注入（用于 PuzzleAI 的谜题上下文）</li>
 *   <li>自定义 TLSv1.2 SSL 上下文（解决 DeepSeek API 的 TLS 兼容性问题）</li>
 *   <li>响应解析：优先提取 content 字段，回退到 reasoning_content（思维链输出）</li>
 * </ul></p>
 *
 * <p>配置项（application.yml）：
 * <ul>
 *   <li>{@code yuzu.llm.base-url} —— API 基础地址</li>
 *   <li>{@code yuzu.llm.api-key} —— 认证密钥</li>
 *   <li>{@code yuzu.llm.model} —— 模型名称</li>
 * </ul></p>
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    public static class LlmCallException extends RuntimeException {
        public LlmCallException(String message) { super(message); }
    }

    private static final int MAX_RETRIES = 5;
    private static final long BASE_RETRY_DELAY_MS = 3000;

    @Value("${yuzu.llm.base-url}")
    private volatile String baseUrl;

    @Value("${yuzu.llm.api-key}")
    private volatile String apiKey;

    @Value("${yuzu.llm.model}")
    private volatile String model;

    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }

    public void updateConfig(String baseUrl, String apiKey, String model) {
        if (baseUrl != null && !baseUrl.isBlank()) this.baseUrl = baseUrl;
        if (apiKey != null && !apiKey.isBlank()) this.apiKey = apiKey;
        if (model != null && !model.isBlank()) this.model = model;
        log.info("LlmService config updated: baseUrl={}, model={}", this.baseUrl, this.model);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient client = createClient();

    /**
     * 创建自定义 HttpClient —— 配置 TLSv1.2 和宽松的证书验证。
     *
     * <p>DeepSeek API 要求 TLSv1.2 协议，而默认 HttpClient 可能使用
     * 不兼容的 TLS 版本。此处通过自定义 {@link SSLContext} 强制使用
     * TLSv1.2，并使用信任所有证书的 {@link TrustManager} 解决
     * 证书链验证问题。</p>
     *
     * <p>如果 SSL 上下文初始化失败，回退到默认 HttpClient。</p>
     *
     * @return 配置好的 HttpClient 实例
     */
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

    /**
     * 无对话历史的 LLM 调用 —— 便捷方法。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @return LLM 生成的文本，或错误信息
     */
    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, null);
    }

    public String chat(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        return chat(systemPrompt, userMessage, history, null, null, null);
    }

    public String chat(String systemPrompt, String userMessage, List<Map<String, String>> history,
                       String overrideBaseUrl, String overrideApiKey, String overrideModel) {
        String useBaseUrl = (overrideBaseUrl != null && !overrideBaseUrl.isBlank()) ? overrideBaseUrl : this.baseUrl;
        String useApiKey = (overrideApiKey != null && !overrideApiKey.isBlank()) ? overrideApiKey : this.apiKey;
        String useModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : this.model;
        String requestBody = buildRequestBody(useModel, systemPrompt, userMessage, history);
        String apiUrl = useBaseUrl + "/chat/completions";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + useApiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(90))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return extractContent(response.body());
                }

                log.warn("LLM request failed with status {} (attempt {}/{}) url={}", response.statusCode(), attempt, MAX_RETRIES, useBaseUrl);

                if (attempt < MAX_RETRIES) {
                    long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                    Thread.sleep(delay);
                } else {
                    throw new LlmCallException("LLM 请求失败（HTTP " + response.statusCode() + "），已重试 " + MAX_RETRIES + " 次");
                }

            } catch (IOException | InterruptedException e) {
                log.warn("LLM connection error: {} (attempt {}/{}) url={}", e.getMessage(), attempt, MAX_RETRIES, useBaseUrl);

                if (attempt < MAX_RETRIES) {
                    long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } else {
                    throw new LlmCallException("LLM 连接失败：" + e.getMessage() + "，已重试 " + MAX_RETRIES + " 次");
                }
            }
        }

        throw new LlmCallException("LLM 请求失败：超过最大重试次数");
    }

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

            if (response.statusCode() == 200) {
                return null;
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return "API 认证失败（HTTP " + response.statusCode() + "），请检查 apiKey 是否正确";
            }
            if (response.statusCode() == 404) {
                return "API 地址无效（HTTP 404），请检查 baseUrl 是否正确";
            }
            return "API 返回错误（HTTP " + response.statusCode() + "）";
        } catch (IOException e) {
            return "无法连接到 API 地址：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "API 校验被中断";
        }
    }

    private String buildRequestBody(String useModel, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(useModel).append("\",\"messages\":[");

        json.append("{\"role\":\"system\",\"content\":").append(CodeUtils.jsonEscape(systemPrompt)).append("}");

        if (history != null) {
            for (Map<String, String> h : history) {
                String role = h.get("role");
                if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role)) {
                    role = "user";
                }
                json.append(",{\"role\":\"").append(role)
                    .append("\",\"content\":").append(CodeUtils.jsonEscape(h.get("content"))).append("}");
            }
        }

        json.append(",{\"role\":\"user\",\"content\":").append(CodeUtils.jsonEscape(userMessage)).append("}");
        json.append("],\"temperature\":0.8,\"max_tokens\":2048}");

        return json.toString();
    }

    /**
     * 从 LLM API 响应中提取生成内容。
     *
     * <p>提取优先级：
     * <ol>
     *   <li>{@code choices[0].message.content} —— 标准输出</li>
     *   <li>{@code choices[0].message.reasoning_content} —— 思维链输出（DeepSeek R1 等模型）</li>
     * </ol></p>
     *
     * @param responseBody API 返回的 JSON 字符串
     * @return 提取的文本内容，解析失败返回空字符串
     */
    private String extractContent(String responseBody) {
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
}
