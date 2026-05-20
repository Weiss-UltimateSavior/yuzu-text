package com.yuzugame.model;

import java.util.List;

/**
 * 地图配置 —— 定义游戏中的每个可探索场景。
 *
 * <p>对应数据文件：{@code data/maps.json}</p>
 *
 * <p>地图是游戏章节推进的载体，每个地图包含：
 * <ul>
 *   <li>基本信息：ID、名称、章节归属、描述、氛围</li>
 *   <li>谜题列表：该地图中包含的谜题 ID</li>
 *   <li>物品列表：该地图中可发现的物品 ID</li>
 *   <li>NPC 列表：该地图中可遭遇的 NPC ID</li>
 *   <li>地图连接：下一地图 ID、出口提示</li>
 *   <li>锁定条件：解锁所需条件表达式</li>
 *   <li>系统提示词：发送给地图 AI 的场景描述指令</li>
 * </ul></p>
 */
public class MapConfig {
    private String id;
    private String name;
    private String chapter;
    private String chapterName;
    private String description;
    private String atmosphere;
    private List<String> puzzles;
    private String nextMapId;
    private boolean locked = true;
    private String unlockCondition;
    private List<String> items;
    private List<String> npcIds;
    private String exitHint;
    private String systemPrompt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getChapter() { return chapter; }
    public void setChapter(String chapter) { this.chapter = chapter; }
    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAtmosphere() { return atmosphere; }
    public void setAtmosphere(String atmosphere) { this.atmosphere = atmosphere; }
    public List<String> getPuzzles() { return puzzles; }
    public void setPuzzles(List<String> puzzles) { this.puzzles = puzzles; }
    public String getNextMapId() { return nextMapId; }
    public void setNextMapId(String nextMapId) { this.nextMapId = nextMapId; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public String getUnlockCondition() { return unlockCondition; }
    public void setUnlockCondition(String unlockCondition) { this.unlockCondition = unlockCondition; }
    public List<String> getItems() { return items; }
    public void setItems(List<String> items) { this.items = items; }
    public List<String> getNpcIds() { return npcIds; }
    public void setNpcIds(List<String> npcIds) { this.npcIds = npcIds; }
    public String getExitHint() { return exitHint; }
    public void setExitHint(String exitHint) { this.exitHint = exitHint; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}
