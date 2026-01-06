package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

@PlanningEntity
public class ProductionAssignment {

    @PlanningId
    private long id;

    // 固定事实：每条产线在某一时段的"格子"
    private ProductionLine line;
    private TimeSlot timeSlot;

    // 规划变量：选择本时段在本产线上运行哪个工艺（可为空=空闲）
    @PlanningVariable(valueRangeProviderRefs = "routerRange", nullable = true)
    private Router router;

    public ProductionAssignment() {}

    public ProductionAssignment(long id, ProductionLine line, TimeSlot timeSlot) {
        this.id = id;
        this.line = line;
        this.timeSlot = timeSlot;
    }

    public long getId() { return id; }
    public ProductionLine getLine() { return line; }
    public TimeSlot getTimeSlot() { return timeSlot; }
    public Router getRouter() { return router; }

    public void setId(long id) { this.id = id; }
    public void setLine(ProductionLine line) { this.line = line; }
    public void setTimeSlot(TimeSlot timeSlot) { this.timeSlot = timeSlot; }
    public void setRouter(Router router) { this.router = router; }

    // 派生属性
    public Item getProducedItem() {
        return router == null ? null : router.getItem();
    }

    public int getProducedQuantity() {
        return router == null ? 0 : router.getSpeedPerHour();
    }

    @Override
    public String toString() {
        return "Assignment{" + line + "@" + timeSlot + " -> " + (router == null ? "IDLE" : router) + "}";
    }
}