package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.domain.variable.CustomShadowVariable;
import org.optaplanner.core.api.domain.variable.PlanningVariableReference;

@PlanningEntity
public class TaskPart {
    private Long id;
    // 所属原始任务
    private Task task;
    // 该分片的数量
    private int quantity;

    // 规划变量：分配到具体的产线-日期-时间段槽位
    @PlanningVariable(valueRangeProviderRefs = "slotRange")
    private LineShiftSlot slot;

    // 规划变量：选择工艺
    @PlanningVariable(valueRangeProviderRefs = "routerRange")
    private Router router;

    // 新增：槽位内顺序号（越小越早）。同一槽位内按该值排序。
    @PlanningVariable(valueRangeProviderRefs = "indexRange")
    private Integer indexInSlot;

    // 影子变量：由监听器根据 slot/router/indexInSlot 顺序累计计算
    @CustomShadowVariable(variableListenerClass = TaskPartTimeUpdatingVariableListener.class,
            sources = {
                    @PlanningVariableReference(variableName = "slot"),
                    @PlanningVariableReference(variableName = "router"),
                    @PlanningVariableReference(variableName = "indexInSlot")
            })
    private Long startIndex;

    private Long endIndex;

    public TaskPart() {}

    public TaskPart(Long id, Task task, int quantity) {
        this.id = id;
        this.task = task;
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Task getTask() { return task; }
    public void setTask(Task task) { this.task = task; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public LineShiftSlot getSlot() { return slot; }
    public void setSlot(LineShiftSlot slot) { this.slot = slot; }

    public Router getRouter() { return router; }
    public void setRouter(Router router) { this.router = router; }

    public Integer getIndexInSlot() { return indexInSlot; }
    public void setIndexInSlot(Integer indexInSlot) { this.indexInSlot = indexInSlot; }

    public Long getStartIndex() { return startIndex; }
    public void setStartIndex(Long startIndex) { this.startIndex = startIndex; }

    public Long getEndIndex() { return endIndex; }
    public void setEndIndex(Long endIndex) { this.endIndex = endIndex; }

    // 便捷访问：当前分片所在产线（来自 slot）
    public Line getLine() { return slot == null ? null : slot.getLine(); }

    // 便捷访问：物料（来自 Task）
    public Item getItem() { return task == null ? null : task.getItem(); }

    // 占用分钟数（产线×工艺节拍）
    public int getRequiredMinutes() {
        if (slot == null || router == null) {
            return 0;
        }
        int mpu = slot.getLine().getMinutesPerUnit(router); // 分钟/件
        long req = (long) mpu * (long) quantity;
        return req > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) req;
    }
}