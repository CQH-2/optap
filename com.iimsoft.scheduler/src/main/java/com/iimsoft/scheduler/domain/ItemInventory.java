package com.iimsoft.scheduler.domain;

/**
 * 物料库存事实：初始在库（不随解变量变化）
 */
public class ItemInventory {

    private Item item;
    private int initialOnHand;

    public ItemInventory() {
    }

    public ItemInventory(Item item, int initialOnHand) {
        this.item = item;
        this.initialOnHand = initialOnHand;
    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public int getInitialOnHand() {
        return initialOnHand;
    }

    public void setInitialOnHand(int initialOnHand) {
        this.initialOnHand = initialOnHand;
    }

    @Override
    public String toString() {
        return item + " onHand=" + initialOnHand;
    }
}