package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * NPC 配置 —— 定义游戏中的非玩家角色。
 *
 * <p>对应数据文件：{@code data/npcs.json}</p>
 *
 * <p>每个 NPC 包含：
 * <ul>
 *   <li>基本信息：ID、名称、身份描述</li>
 *   <li>角色设定：性格、背景故事、说话风格</li>
 *   <li>知晓信息：NPC 掌握的线索列表（在对话中逐步透露）</li>
 *   <li>出现条件：{@code appearCondition} 条件表达式，满足时 NPC 才会出现在场景中</li>
 *   <li>对话条件：{@code dialogueCondition} 条件表达式，满足时才能进行深度对话</li>
 *   <li>对话提示：未满足对话条件时显示的提示文本</li>
 * </ul></p>
 *
 * @see com.yuzugame.engine.ConditionEvaluator 条件表达式语法
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NpcConfig {
    private String id;
    private String name;
    private String description;
    private String personality;
    private String background;
    private List<String> knownInfo;
    private String dialogueStyle;
    private String appearCondition;
    private String dialogueCondition;
    private String dialogueHint;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public List<String> getKnownInfo() { return knownInfo; }
    public void setKnownInfo(List<String> knownInfo) { this.knownInfo = knownInfo; }
    public String getDialogueStyle() { return dialogueStyle; }
    public void setDialogueStyle(String dialogueStyle) { this.dialogueStyle = dialogueStyle; }
    public String getAppearCondition() { return appearCondition; }
    public void setAppearCondition(String appearCondition) { this.appearCondition = appearCondition; }
    public String getDialogueCondition() { return dialogueCondition; }
    public void setDialogueCondition(String dialogueCondition) { this.dialogueCondition = dialogueCondition; }
    public String getDialogueHint() { return dialogueHint; }
    public void setDialogueHint(String dialogueHint) { this.dialogueHint = dialogueHint; }
}
