package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 故事配置 —— 定义游戏的章节和全局参数。
 *
 * <p>对应数据文件：{@code data/story.json}</p>
 *
 * <p>包含以下核心配置：
 * <ul>
 *   <li>{@link ChapterDef} 章节定义 —— 章节顺序、名称、对应地图、关键事件</li>
 *   <li>{@link SanityDecayCurve} 理智衰减曲线 —— 不同回合段的每回合理智衰减值</li>
 *   <li>导演系统提示词 —— 发送给导演 AI 的全局叙事指令</li>
 *   <li>最大回合数 —— 游戏的最大回合限制</li>
 * </ul></p>
 *
 * <p>结局规则已迁移至 {@code data/endings.json}，由 {@link EndingRuleConfig} 定义。</p>
 *
 * @see EndingRuleConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoryConfig {
    private List<ChapterDef> chapters;
    private String directorSystemPrompt;
    private int maxTurns;
    private List<SanityDecayCurve> sanityDecayCurve;

    public List<ChapterDef> getChapters() { return chapters; }
    public void setChapters(List<ChapterDef> chapters) { this.chapters = chapters; }
    public String getDirectorSystemPrompt() { return directorSystemPrompt; }
    public void setDirectorSystemPrompt(String directorSystemPrompt) { this.directorSystemPrompt = directorSystemPrompt; }
    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    public List<SanityDecayCurve> getSanityDecayCurve() { return sanityDecayCurve; }
    public void setSanityDecayCurve(List<SanityDecayCurve> sanityDecayCurve) { this.sanityDecayCurve = sanityDecayCurve; }

    /**
     * 根据当前回合数查询对应的理智衰减值。
     *
     * <p>遍历衰减曲线列表，找到包含当前回合的区间并返回其衰减值。
     * 如果没有匹配的区间，默认返回 1。</p>
     *
     * @param turn 当前回合数
     * @return 该回合应扣除的理智值
     */
    public int getSanityDecayForTurn(int turn) {
        if (sanityDecayCurve == null) return 1;
        for (SanityDecayCurve curve : sanityDecayCurve) {
            if (turn >= curve.getFromTurn() && turn <= curve.getToTurn()) {
                return curve.getDecayPerTurn();
            }
        }
        return 1;
    }

    /**
     * 章节定义 —— 描述游戏的一个叙事阶段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChapterDef {
        private String id;
        private String name;
        private String mapId;
        private int order;
        private String keyEvent;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMapId() { return mapId; }
        public void setMapId(String mapId) { this.mapId = mapId; }
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        public String getKeyEvent() { return keyEvent; }
        public void setKeyEvent(String keyEvent) { this.keyEvent = keyEvent; }
    }

    /**
     * 理智衰减曲线 —— 定义不同回合段的每回合理智衰减值。
     *
     * <p>例如：前 20 回合每回合衰减 1，20~50 回合每回合衰减 2，
     * 50 回合后每回合衰减 3，模拟逐渐加速的理智恶化。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SanityDecayCurve {
        private int fromTurn;
        private int toTurn;
        private int decayPerTurn;
        public int getFromTurn() { return fromTurn; }
        public void setFromTurn(int fromTurn) { this.fromTurn = fromTurn; }
        public int getToTurn() { return toTurn; }
        public void setToTurn(int toTurn) { this.toTurn = toTurn; }
        public int getDecayPerTurn() { return decayPerTurn; }
        public void setDecayPerTurn(int decayPerTurn) { this.decayPerTurn = decayPerTurn; }
    }
}
