package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 主角（柚子）配置 —— 定义核心同伴角色的属性和行为规则。
 *
 * <p>对应数据文件：{@code data/protagonist.json}</p>
 *
 * <p>柚子是贯穿整个游戏的核心 NPC 同伴，配置包含：
 * <ul>
 *   <li>基本信息：名称（中/英文）、身份描述</li>
 *   <li>角色设定：性格、背景故事</li>
 *   <li>系统提示词：发送给主角 AI 的角色扮演指令</li>
 *   <li>好感度参数：初始好感度（initialAffection）、最大好感度（maxAffection）</li>
 * </ul></p>
 *
 * @see com.yuzugame.agent.ProtagonistAI
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProtagonistConfig {
    private String name;
    private String nameEn;
    private String description;
    private String personality;
    private String background;
    private String systemPrompt;
    private int initialAffection;
    private int maxAffection;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public int getInitialAffection() { return initialAffection; }
    public void setInitialAffection(int initialAffection) { this.initialAffection = initialAffection; }
    public int getMaxAffection() { return maxAffection; }
    public void setMaxAffection(int maxAffection) { this.maxAffection = maxAffection; }
}
