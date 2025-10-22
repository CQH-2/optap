package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

// 注意：Task 现在是问题事实，不再是 PlanningEntity
public class Task {
    private Long id;
    private Item item;       // 物料
    private int quantity;    // 产量（单位需与 Slot.capacityUnits 对齐）

    // 交期（由 Demand/BOM 展开得到），用于迟期惩罚（由 TaskPart 规则去读取）
    private LocalDate dueDate;

    public Task() {}
    public Task(Long id, Item item, int quantity) {
        this.id = id; this.item = item; this.quantity = quantity;
    }
    public Task(Long id, Item item, int quantity, LocalDate dueDate) {
        this.id = id; this.item = item; this.quantity = quantity; this.dueDate = dueDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    // 交期换算为分钟序（以当天结束为界），供 DRL 从 TaskPart.task 访问
    public Long getDueEndIndexMinutes() {
        if (dueDate == null) return null;
        return dueDate.toEpochDay() * 24L * 60L + 24L * 60L;
    }
}