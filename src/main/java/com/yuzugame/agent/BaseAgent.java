package com.yuzugame.agent;

import com.yuzugame.model.GameSession;
import com.yuzugame.service.BaseLlmService;
import com.yuzugame.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Agent 基类 —— 提供所有 AI Agent 共享的工具方法。
 *
 * <p>提取了各 Agent 中重复出现的：
 * <ul>
 *   <li>{@code chatWithSession} —— 支持自定义 LLM 的对话调用，含 C3 降级保护</li>
 *   <li>{@code nullToEmpty} —— null 安全转空字符串</li>
 *   <li>{@code safeReplace} —— 模板占位符替换</li>
 *   <li>{@code appendSection} —— 条件性追加段落</li>
 *   <li>{@code appendNonNull} —— 非空追加</li>
 * </ul></p>
 */
public abstract class BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

    protected final LlmService llm;

    protected BaseAgent(LlmService llm) {
        this.llm = llm;
    }

    protected String chatWithSession(GameSession session, String systemPrompt, String userMessage) {
        return chatWithSession(session, systemPrompt, userMessage, null);
    }

    /**
     * C3 修复：Agent 级 LLM 降级保护。
     * 单个 Agent 调用失败时返回空字符串而非抛出异常，
     * 避免一个 Agent 失败导致整轮输出全部丢失。
     *
     * <p>但 {@link BaseLlmService.LlmCallException}（重试耗尽后的致命错误）
     * 仍需向上抛出，以便 GameEngine 执行会话回滚。</p>
     */
    protected String chatWithSession(GameSession session, String systemPrompt, String userMessage, List<Map<String, String>> history) {
        try {
            if (session != null && session.hasCustomLlm()) {
                return llm.chat(systemPrompt, userMessage, history, session.getCustomLlmBaseUrl(), session.getCustomLlmApiKey(), session.getCustomLlmModel());
            }
            return llm.chat(systemPrompt, userMessage, history);
        } catch (BaseLlmService.LlmCallException e) {
            // 致命错误：重试耗尽，需让 GameEngine 回滚整个回合
            throw e;
        } catch (Exception e) {
            // 非致命错误：降级为空响应，仅丢失当前 Agent 输出
            log.warn("[{}] LLM call failed, degrading to empty response: {}", getClass().getSimpleName(), e.getMessage());
            return "";
        }
    }

    protected static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    protected static String safeReplace(String template, String... pairs) {
        if (template == null) return "";
        String result = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace(pairs[i], pairs[i + 1] != null ? pairs[i + 1] : "");
        }
        return result;
    }

    protected static void appendSection(StringBuilder sb, String text) {
        if (text != null && !text.isBlank()) {
            sb.append("\n").append(text).append("\n");
        }
    }

    protected static void appendNonNull(StringBuilder sb, String text) {
        if (text != null) {
            sb.append(text);
        }
    }
}
