package com.yuzugame.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feedbacks")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String contact; // 玩家联系方式（可选）

    @Column(columnDefinition = "TEXT")
    private String content; // 反馈正文

    @Column(length = 64)
    private String sessionId; // 关联的游戏会话ID（可选）

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now(); // 持久化前自动填充时间戳
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Instant getCreatedAt() { return createdAt; }
}
