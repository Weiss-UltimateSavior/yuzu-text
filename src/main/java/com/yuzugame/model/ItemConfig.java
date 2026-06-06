package com.yuzugame.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 物品配置 —— 定义游戏中的可拾取物品。
 *
 * <p>对应数据文件：{@code data/items.json}</p>
 *
 * <p>每个物品包含：
 * <ul>
 *   <li>ID —— 唯一标识符，用于控制标签引用（如 ITEM:FOUND:item_pipe_sample）</li>
 *   <li>名称 —— 物品的显示名称</li>
 *   <li>类型 —— 物品分类（如 key、tool、document、consumable）</li>
 *   <li>描述 —— 物品的详细描述文本</li>
 * </ul></p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemConfig {
    private String id;
    private String name;
    private String type;
    private String description;
    private Integer sanityRecovery;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getSanityRecovery() { return sanityRecovery; }
    public void setSanityRecovery(Integer sanityRecovery) { this.sanityRecovery = sanityRecovery; }
}
