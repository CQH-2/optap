package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

public class Requirement {
    private Long id;
    private Item item;
    private int quantity;        // 需求数量（毛需求）
    private LocalDate dueDate;   // 需求到位日期（以当天结束为界）

    public Requirement() {}
    public Requirement(Long id, Item item, int quantity, LocalDate dueDate) {
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

    // 交期当天 24:00 的时间索引（分钟）
    public Long getDueEndIndexMinutes() {
        if (dueDate == null) return null;
        return dueDate.toEpochDay() * 24L * 60L + 24L * 60L;
    }
}