package com.yuzugame.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家模型 —— 三维属性与背包系统。
 *
 * <p>玩家拥有三个核心属性（取值范围 0~100，由 setter 自动钳制）：
 * <ul>
 *   <li>{@code sanity}（理智）—— 初始 100，随游戏推进自然衰减，降至 0 触发 FAIL 结局</li>
 *   <li>{@code revelation}（揭露度）—— 初始 0，通过解谜和探索增长，达到阈值触发结局</li>
 *   <li>{@code affection}（柚子好感度）—— 初始 30，通过与柚子互动增长</li>
 * </ul></p>
 *
 * <p>属性范围钳制：所有 setter 和 add 方法内置 [0, 100] 范围截断。
 * 调整范围：修改各 setter 中 {@code Math.max(0, Math.min(100, ...))} 的上下界。
 * 调整初始值：修改下方字段声明中的默认值（如 {@code sanity = 100}）。</p>
 *
 * <p>背包系统：使用 {@link List} 存储物品 ID，同一物品不会重复添加。</p>
 */
public class Player {

    private int sanity = 100;       // 理智：初始100，范围[0,100]
    private int revelation = 0;     // 揭露度：初始0，范围[0,100]
    private int affection = 30;     // 好感度：初始30，范围[0,100]
    private List<String> inventory = new ArrayList<>();

    public int getSanity() { return sanity; }
    public void setSanity(int sanity) { this.sanity = Math.max(0, Math.min(100, sanity)); }
    public void addSanity(int delta) { setSanity(sanity + delta); }

    public int getRevelation() { return revelation; }
    public void setRevelation(int revelation) { this.revelation = Math.max(0, Math.min(100, revelation)); }
    public void addRevelation(int delta) { setRevelation(revelation + delta); }

    public int getAffection() { return affection; }
    public void setAffection(int affection) { this.affection = Math.max(0, Math.min(100, affection)); }
    public void addAffection(int delta) { setAffection(affection + delta); }

    public void copyFrom(Player other) {
        if (other == null) return;
        this.sanity = other.sanity;
        this.revelation = other.revelation;
        this.affection = other.affection;
        this.inventory.clear();
        this.inventory.addAll(other.inventory);
    }

    public List<String> getInventory() { return Collections.unmodifiableList(inventory); }
    public void setInventory(List<String> inventory) { this.inventory.clear(); this.inventory.addAll(inventory); }
    public boolean hasItem(String itemId) { return inventory.contains(itemId); }
    public void addItem(String itemId) { if (!inventory.contains(itemId)) inventory.add(itemId); }
    public void removeItem(String itemId) { inventory.remove(itemId); }
}
