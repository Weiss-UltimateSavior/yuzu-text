package com.yuzugame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 2000;

    @Value("${yuzu.llm.base-url}")
    private String baseUrl;

    @Value("${yuzu.llm.api-key}")
    private String apiKey;

    @Value("${yuzu.llm.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String getApiUrl() {
        return baseUrl + "/chat/completions";
    }

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
            sslContext.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            }, null);
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

    /**
     * 带对话历史的 LLM 调用 —— 核心方法。
     *
     * <p>构建 OpenAI 兼容的 Chat Completion 请求，消息顺序为：
     * <ol>
     *   <li>system —— 系统提示词（Agent 的角色和规则定义）</li>
     *   <li>history —— 历史对话（assistant 角色，用于谜题多轮交互）</li>
     *   <li>user —— 当前用户输入</li>
     * </ol></p>
     *
     * <p>请求参数：temperature=0.8（适度创造性），max_tokens=2048，
     * 超时时间 60 秒。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @param history 对话历史（可为 null），每条记录含 role 和 content
     * @return LLM 生成的文本；HTTP 错误返回 {@code [LLM Error: statusCode]}；
     *         连接异常返回 {@code [LLM Connection Error: message]}
     */
    public String chat(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        String requestBody = buildRequestBody(systemPrompt, userMessage, history);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getApiUrl()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(90))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return extractContent(response.body());
                }

                log.warn("LLM request failed with status {} (attempt {}/{})", response.statusCode(), attempt, MAX_RETRIES);

                if (attempt < MAX_RETRIES) {
                    long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                    Thread.sleep(delay);
                } else {
                    return "[LLM Error: " + response.statusCode() + "]";
                }

            } catch (IOException | InterruptedException e) {
                log.warn("LLM connection error: {} (attempt {}/{})", e.getMessage(), attempt, MAX_RETRIES);

                if (attempt < MAX_RETRIES) {
                    long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } else {
                    return "[LLM Connection Error: " + e.getMessage() + "]";
                }
            }
        }

        return "[LLM Error: max retries exceeded]";
    }

    private String buildRequestBody(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(model).append("\",\"messages\":[");

        json.append("{\"role\":\"system\",\"content\":").append(jsonEscape(systemPrompt)).append("}");

        if (history != null) {
            for (Map<String, String> h : history) {
                json.append(",{\"role\":\"").append(h.get("role"))
                    .append("\",\"content\":").append(jsonEscape(h.get("content"))).append("}");
            }
        }

        json.append(",{\"role\":\"user\",\"content\":").append(jsonEscape(userMessage)).append("}");
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

    /**
     * JSON 字符串转义 —— 手动构建 JSON 以避免引入额外依赖。
     *
     * <p>处理特殊字符：{@code "} → {@code \"}、{@code \} → {@code \\}、
     * 换行/回车/制表符 → 对应的 JSON 转义序列。</p>
     *
     * @param text 原始文本
     * @return 用双引号包裹的 JSON 安全字符串
     */
    private String jsonEscape(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
