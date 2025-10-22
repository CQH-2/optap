package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

@PlanningEntity
public class Task {
    private Long id;
    private Item item;       // 物料
    private int quantity;    // 产量（单位需与 Slot.capacityUnits 对齐）

    // 规划变量：分配到具体的产线-日期-时间段槽位
    @PlanningVariable(valueRangeProviderRefs = "slotRange")
    private LineShiftSlot slot;

    // 规划变量：选择工艺
    @PlanningVariable(valueRangeProviderRefs = "routerRange")
    private Router router;

    public Task() {}
    public Task(Long id, Item item, int quantity) {
        this.id = id; this.item = item; this.quantity = quantity;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public LineShiftSlot getSlot() { return slot; }
    public void setSlot(LineShiftSlot slot) { this.slot = slot; }

    public Router getRouter() { return router; }
    public void setRouter(Router router) { this.router = router; }

    // 便捷访问：当前任务所在产线（来自 slot）
    public Line getLine() { return slot == null ? null : slot.getLine(); }
}