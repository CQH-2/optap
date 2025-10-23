package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

public class DemandOrder {
    private Item item;
    private int quantity;
    private LocalDate dueDate;
    // 对应的到期时间槽索引（用于累计计算，取当天最后一个班次小时）
    private int dueTimeSlotIndex;

    public DemandOrder() {}

    public DemandOrder(Item item, int quantity, LocalDate dueDate, int dueTimeSlotIndex) {
        this.item = item;
        this.quantity = quantity;
        this.dueDate = dueDate;
        this.dueTimeSlotIndex = dueTimeSlotIndex;
    }

    public Item getItem() { return item; }
    public int getQuantity() { return quantity; }
    public LocalDate getDueDate() { return dueDate; }
    public int getDueTimeSlotIndex() { return dueTimeSlotIndex; }

    public void setItem(Item item) { this.item = item; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setDueTimeSlotIndex(int dueTimeSlotIndex) { this.dueTimeSlotIndex = dueTimeSlotIndex; }

    @Override
    public String toString() {
        return "Demand{" + item + " x" + quantity + " due " + dueDate + " @#" + dueTimeSlotIndex + "}";
    }
}