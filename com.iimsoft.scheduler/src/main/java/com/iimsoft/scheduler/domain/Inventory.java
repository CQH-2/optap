package com.iimsoft.scheduler.domain;

public class Inventory {
    private Long id;
    private Item item;
    private int onHandQty; // 期初库存（取当前时点的库存）

    public Inventory() {}
    public Inventory(Long id, Item item, int onHandQty) {
        this.id = id;
        this.item = item;
        this.onHandQty = onHandQty;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public int getOnHandQty() { return onHandQty; }
    public void setOnHandQty(int onHandQty) { this.onHandQty = onHandQty; }
}