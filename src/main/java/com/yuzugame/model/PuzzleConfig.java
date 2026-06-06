package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 谜题配置 —— 定义游戏中的谜题挑战。
 *
 * <p>对应数据文件：{@code data/puzzles.json}</p>
 *
 * <p>每个谜题包含：
 * <ul>
 *   <li>基本信息：ID、名称、难度（1~5）、所属地图</li>
 *   <li>谜题描述：面向玩家的谜题场景描述</li>
 *   <li>系统提示词：发送给谜题 AI 的指令（含解谜规则和判定逻辑）</li>
 *   <li>成功条件：描述什么样的玩家输入算作解谜成功</li>
 *   <li>尝试限制：最大尝试次数，超过后强制失败</li>
 *   <li>失败惩罚：理智扣减值和失败叙事文本</li>
 *   <li>前置物品：解谜所需的关键物品 ID（可为 null）</li>
 * </ul></p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PuzzleConfig {
    private String id;
    private String name;
    private int difficulty;
    private String requiredItemId;
    private String mapId;
    private String description;
    private String systemPrompt;
    private String solutionCriteria;
    private int maxAttempts;
    private int failSanityPenalty;
    private String failNarrative;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }
    public String getRequiredItemId() { return requiredItemId; }
    public void setRequiredItemId(String requiredItemId) { this.requiredItemId = requiredItemId; }
    public String getMapId() { return mapId; }
    public void setMapId(String mapId) { this.mapId = mapId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getSolutionCriteria() { return solutionCriteria; }
    public void setSolutionCriteria(String solutionCriteria) { this.solutionCriteria = solutionCriteria; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getFailSanityPenalty() { return failSanityPenalty; }
    public void setFailSanityPenalty(int failSanityPenalty) { this.failSanityPenalty = failSanityPenalty; }
    public String getFailNarrative() { return failNarrative; }
    public void setFailNarrative(String failNarrative) { this.failNarrative = failNarrative; }
}
