package com.yuzugame.model;

import java.util.Map;

/**
 * 兑换码配置 —— 对应 {@code data/redemption_codes.json} 中的一条兑换码规则。
 *
 * <p>每个兑换码包含：
 * <ul>
 *   <li>{@code code} —— 兑换码口令（不区分大小写）</li>
 *   <li>{@code description} —— 兑换码说明</li>
 *   <li>{@code rewards} —— 奖励数值映射，键为属性名（sanity/revelation/affection/turn/score），值为增减量</li>
 *   <li>{@code maxUses} —— 每个会话最多使用次数（0=无限）</li>
 *   <li>{@code active} —— 是否启用</li>
 * </ul></p>
 *
 * <h3>支持的奖励属性：</h3>
 * <ul>
 *   <li>{@code sanity} —— 理智（0~100，自动截断）</li>
 *   <li>{@code revelation} —— 揭露度（0~100，自动截断）</li>
 *   <li>{@code affection} —— 柚子好感度（0~100，自动截断）</li>
 *   <li>{@code turn} —— 回合数（增减）</li>
 *   <li>{@code score} —— 分数（通过属性变化间接影响）</li>
 * </ul></p>
 *
 * <h3>添加新兑换码步骤：</h3>
 * <ol>
 *   <li>在 {@code redemption_codes.json} 中追加一条规则</li>
 *   <li>无需修改 Java 代码</li>
 * </ol>
 */
public class RedemptionCodeConfig {

    private String code;
    private String description;
    private Map<String, Integer> rewards;
    private int maxUses = 1;
    private boolean active = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Integer> getRewards() { return rewards; }
    public void setRewards(Map<String, Integer> rewards) { this.rewards = rewards; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
