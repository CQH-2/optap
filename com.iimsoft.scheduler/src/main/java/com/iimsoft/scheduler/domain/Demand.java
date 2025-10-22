package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

public class Demand {
    private Long id;
    private Item item;          // 顶层或中间层需求物料
    private int quantity;       // 需求数量
    private LocalDate dueDate;  // 要求完工日期（以当天结束为准）

    public Demand() {}
    public Demand(Long id, Item item, int quantity, LocalDate dueDate) {
        this.id = id;
        this.item = item;
        this.quantity = quantity;
        this.dueDate = dueDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}