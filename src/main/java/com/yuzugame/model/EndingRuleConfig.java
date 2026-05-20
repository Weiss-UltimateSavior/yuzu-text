package com.yuzugame.model;

import java.util.List;

/**
 * 结局规则配置 —— 对应 {@code data/endings.json} 中的一条结局规则。
 *
 * <p>每条规则包含：
 * <ul>
 *   <li>{@code type} —— 结局类型（FAIL/FINAL/PERFECT/SECRET 等）</li>
 *   <li>{@code description} —— 中文说明，用于日志和文档</li>
 *   <li>{@code priority} —— 优先级，数字越小越优先（首次匹配即返回）</li>
 *   <li>{@code conditions} —— 触发条件列表，每条条件为字段-运算符-值的三元组</li>
 *   <li>{@code logic} —— 条件之间的逻辑关系（AND/OR）</li>
 *   <li>{@code narrativeHint} —— 结局叙事提示，传给导演AI生成结局旁白</li>
 * </ul></p>
 *
 * <p>结局触发基于数值条件判定，结局旁白则由 LLM 结合玩家属性和完整对话历史
 * 生成个性化叙事，使每个结局都贴合玩家的实际游玩经历。</p>
 *
 * <h3>添加新结局步骤：</h3>
 * <ol>
 *   <li>在 {@code endings.json} 中追加一条规则，设置合适的 priority</li>
 *   <li>如需新的字段路径，在 {@link EndingCondition} 的字段映射中添加</li>
 *   <li>无需修改 Java 代码</li>
 * </ol>
 *
 * <h3>支持的字段路径：</h3>
 * <ul>
 *   <li>{@code player.sanity} —— 玩家理智</li>
 *   <li>{@code player.revelation} —— 揭露度</li>
 *   <li>{@code player.affection} —— 柚子好感度</li>
 *   <li>{@code currentChapter} —— 当前章节ID</li>
 *   <li>{@code turn} —— 当前回合数</li>
 *   <li>{@code score} —— 当前总分</li>
 * </ul>
 *
 * <h3>支持的运算符：</h3>
 * <ul>
 *   <li>{@code eq} —— 等于</li>
 *   <li>{@code neq} —— 不等于</li>
 *   <li>{@code gt} —— 大于</li>
 *   <li>{@code gte} —— 大于等于</li>
 *   <li>{@code lt} —— 小于</li>
 *   <li>{@code lte} —— 小于等于</li>
 * </ul>
 *
 * @see com.yuzugame.agent.DirectorAI#determineEndingAction
 */
public class EndingRuleConfig {

    private String type;
    private String description;
    private int priority;
    private List<EndingCondition> conditions;
    private String logic;
    private String narrativeHint;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public List<EndingCondition> getConditions() { return conditions; }
    public void setConditions(List<EndingCondition> conditions) { this.conditions = conditions; }
    public String getLogic() { return logic; }
    public void setLogic(String logic) { this.logic = logic; }
    public String getNarrativeHint() { return narrativeHint; }
    public void setNarrativeHint(String narrativeHint) { this.narrativeHint = narrativeHint; }

    /**
     * 单条触发条件 —— 字段路径、比较运算符、期望值的三元组。
     *
     * <p>示例：{@code {"field": "player.sanity", "operator": "lte", "value": 0}}
     * 表示"玩家理智 ≤ 0"</p>
     */
    public static class EndingCondition {
        private String field;
        private String operator;
        private Object value;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
    }
}
