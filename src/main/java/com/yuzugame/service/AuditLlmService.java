package com.yuzugame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 审核 LLM 服务 —— 专用于输入安全审核的独立 LLM 通信层。
 *
 * <p>与 {@link LlmService} 完全隔离，使用独立的 API 地址、密钥和模型，
 * 确保审核服务与游戏 AI 服务互不影响：</p>
 *
 * <ul>
 *   <li>游戏 AI（柚子/导演/地图/谜题/NPC）使用 {@link LlmService}</li>
 *   <li>输入审核使用本服务 {@link AuditLlmService}</li>
 * </ul>
 *
 * <p>隔离原因：
 * <ol>
 *   <li>安全隔离 —— 审核服务不可被游戏 AI 的 prompt 污染</li>
 *   <li>成本控制 —— 审核可用更便宜的轻量模型（如 deepseek-chat），游戏 AI 用高质量模型</li>
 * </ol></p>
 *
 * <p>配置项（application.yml）：
 * <ul>
 *   <li>{@code yuzu.audit-llm.base-url} —— 审核 API 基础地址</li>
 *   <li>{@code yuzu.audit-llm.api-key} —— 审核认证密钥</li>
 *   <li>{@code yuzu.audit-llm.model} —— 审核模型名称</li>
 * </ul></p>
 */
@Service
public class AuditLlmService {

    private static final Logger log = LoggerFactory.getLogger(AuditLlmService.class);

    private static final int MAX_RETRIES = 2;
    private static final long BASE_RETRY_DELAY_MS = 1000;

    @Value("${yuzu.audit-llm.base-url:${yuzu.llm.base-url}}")
    private String baseUrl;

    @Value("${yuzu.audit-llm.api-key:${yuzu.llm.api-key}}")
    private String apiKey;

    @Value("${yuzu.audit-llm.model:${yuzu.llm.model}}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient client = createClient();

    private String getApiUrl() {
        return baseUrl + "/chat/completions";
    }

    private static HttpClient createClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 无对话历史的审核 LLM 调用。
     *
     * @param systemPrompt 系统提示词（审核规则）
     * @param userMessage 待审核的玩家输入
     * @return LLM 判定结果
     */
    public String chat(String systemPrompt, String userMessage) {
        String requestBody = buildRequestBody(systemPrompt, userMessage);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getApiUrl()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(45))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return extractContent(response.body());
                }

                log.warn("Audit LLM request failed with status {} (attempt {}/{})",
                        response.statusCode(), attempt, MAX_RETRIES);

                if (attempt < MAX_RETRIES) {
                    long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                    Thread.sleep(delay);
                } else {
                    return "[Audit LLM Error: " + response.statusCode() + "]";
                }

            } catch (IOException | InterruptedException e) {
                log.warn("Audit LLM connection error: {} (attempt {}/{})",
                        e.getMessage(), attempt, MAX_RETRIES);

                if (attempt < MAX_RETRIES) {
                    long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } else {
                    return "[Audit LLM Connection Error: " + e.getMessage() + "]";
                }
            }
        }

        return "[Audit LLM Error: max retries exceeded]";
    }

    private String buildRequestBody(String systemPrompt, String userMessage) {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(model).append("\",\"messages\":[");

        json.append("{\"role\":\"system\",\"content\":").append(jsonEscape(systemPrompt)).append("}");
        json.append(",{\"role\":\"user\",\"content\":").append(jsonEscape(userMessage)).append("}");

        json.append("],\"temperature\":1,\"max_tokens\":512}");

        return json.toString();
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode msg = root.at("/choices/0/message");
            if (msg == null || msg.isNull()) return "";

            JsonNode contentNode = msg.get("content");
            if (contentNode != null && !contentNode.isNull() && !contentNode.asText().isBlank()) {
                return contentNode.asText();
            }

            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String jsonEscape(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
