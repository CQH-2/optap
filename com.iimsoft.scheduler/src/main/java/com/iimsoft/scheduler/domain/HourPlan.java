package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.domain.lookup.PlanningId;

@PlanningEntity
public class HourPlan {
    @PlanningId
    private Long id;

    // 固定事实：该规划位对应的产线-日期-小时
    private LineHourSlot slot;

    // 规划变量：物料（可为空，表示该小时空闲）
    @PlanningVariable(valueRangeProviderRefs = "itemRange", nullable = true)
    private Item item;

    // 规划变量：数量（整数范围 0..maxQuantityPerHour）
    @PlanningVariable(valueRangeProviderRefs = "quantityRange")
    private Integer quantity;

    public HourPlan() {}

    public HourPlan(Long id, LineHourSlot slot) {
        this.id = id;
        this.slot = slot;
        this.quantity = 0;
        this.item = null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LineHourSlot getSlot() { return slot; }
    public void setSlot(LineHourSlot slot) { this.slot = slot; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    // 便捷访问
    public Line getLine() { return slot == null ? null : slot.getLine(); }
    public Long getStartIndex() { return slot == null ? null : slot.getStartIndex(); }
    public Long getEndIndex() { return slot == null ? null : slot.getEndIndex(); }

    // 占用分钟数（产线 × 物料的最优工艺节拍）
    public int getRequiredMinutes() {
        if (slot == null || item == null || quantity == null || quantity <= 0) {
            return 0;
        }
        int mpu = slot.getLine().getMinutesPerUnitForItem(item); // 分钟/件
        long req = (long) mpu * (long) quantity;
        return req > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) req;
    }
}