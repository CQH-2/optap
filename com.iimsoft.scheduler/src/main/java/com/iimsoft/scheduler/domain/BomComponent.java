package com.iimsoft.scheduler.domain;

public class BomComponent {
    private Long id;
    private Item parentItem;   // 父物料（总成/中间件）
    private Item childItem;    // 子物料（零件/原料）
    private int quantityPer;   // 用量：每 1 个父物料需要多少个子物料
    private int leadTimeDays;  // 提前期：子件需比父件早多少天准备好

    public BomComponent() {}
    public BomComponent(Long id, Item parentItem, Item childItem, int quantityPer, int leadTimeDays) {
        this.id = id;
        this.parentItem = parentItem;
        this.childItem = childItem;
        this.quantityPer = quantityPer;
        this.leadTimeDays = leadTimeDays;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Item getParentItem() { return parentItem; }
    public void setParentItem(Item parentItem) { this.parentItem = parentItem; }

    public Item getChildItem() { return childItem; }
    public void setChildItem(Item childItem) { this.childItem = childItem; }

    public int getQuantityPer() { return quantityPer; }
    public void setQuantityPer(int quantityPer) { this.quantityPer = quantityPer; }

    public int getLeadTimeDays() { return leadTimeDays; }
    public void setLeadTimeDays(int leadTimeDays) { this.leadTimeDays = leadTimeDays; }
}