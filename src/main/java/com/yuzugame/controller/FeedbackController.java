package com.yuzugame.controller;

import com.yuzugame.model.Feedback;
import com.yuzugame.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);
    private final FeedbackRepository feedbackRepository;

    public FeedbackController(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody Map<String, String> body) {
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
            log.info("Feedback saved: id={}, sessionId={}, contact={}", feedback.getId(), sessionId, contact);
            return Map.of("ok", true, "message", "感谢你的反馈！");
        } catch (Exception e) {
            log.error("Failed to save feedback: {}", e.getMessage()); // 持久化失败兜底
            return Map.of("ok", false, "error", "提交失败，请稍后再试");
        }
    }
}
