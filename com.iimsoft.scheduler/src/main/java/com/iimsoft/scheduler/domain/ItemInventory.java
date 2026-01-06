package com.iimsoft.scheduler.domain;

import lombok.Data;

/**
 * 物料库存事实：初始在库（不随解变量变化）
 */
@Data
public class ItemInventory {

    private Item item;
    private int initialOnHand;
    private int safetyStock;

    public ItemInventory() {
    }

    public ItemInventory(Item item, int initialOnHand) {
        this.item = item;
        this.initialOnHand = initialOnHand;
    }


    @Override
    public String toString() {
        return item + " onHand=" + initialOnHand;
    }
}