package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

import java.util.Objects;

/**
 * 工序：规划实体。一个订单的一个加工步骤。
 * 规划变量：assignedLine（ProductionLine类型）和 startTime（Long类型，表示开始时间戳）
 */
@PlanningEntity
public class Operation {
    @PlanningId
    private String id;
    
    private Order order; // 所属订单
    private Process process; // 所需工艺
    private int quantity; // 加工数量
    private double standardHours; // 标准工时
    private int sequenceInOrder; // 在订单中的顺序位置（从0开始）

    // 规划变量
    @PlanningVariable(valueRangeProviderRefs = "lineRange", nullable = true)
    private ProductionLine assignedLine;
    
    @PlanningVariable(valueRangeProviderRefs = "timeRange", nullable = true)
    private Long startTime; // 开始时间戳

    public Operation() {}

    public Operation(String id, Order order, Process process, int quantity, double standardHours, int sequenceInOrder) {
        this.id = id;
        this.order = order;
        this.process = process;
        this.quantity = quantity;
        this.standardHours = standardHours;
        this.sequenceInOrder = sequenceInOrder;
    }

    public String getId() { return id; }
    public Order getOrder() { return order; }
    public Process getProcess() { return process; }
    public int getQuantity() { return quantity; }
    public double getStandardHours() { return standardHours; }
    public int getSequenceInOrder() { return sequenceInOrder; }
    public ProductionLine getAssignedLine() { return assignedLine; }
    public Long getStartTime() { return startTime; }

    public void setId(String id) { this.id = id; }
    public void setOrder(Order order) { this.order = order; }
    public void setProcess(Process process) { this.process = process; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setStandardHours(double standardHours) { this.standardHours = standardHours; }
    public void setSequenceInOrder(int sequenceInOrder) { this.sequenceInOrder = sequenceInOrder; }
    public void setAssignedLine(ProductionLine assignedLine) { this.assignedLine = assignedLine; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    /**
     * 计算工序的结束时间
     */
    public Long getEndTime() {
        if (startTime == null) {
            return null;
        }
        // 结束时间 = 开始时间 + 标准工时（小时转为毫秒）
        return startTime + (long)(standardHours * 3600 * 1000);
    }

    /**
     * 获取前序工序（同一订单中的上一个工序）
     */
    public Operation getPredecessor() {
        if (order == null || sequenceInOrder == 0) {
            return null;
        }
        return order.getOperations().stream()
                .filter(op -> op.getSequenceInOrder() == sequenceInOrder - 1)
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Operation)) return false;
        Operation operation = (Operation) o;
        return Objects.equals(id, operation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Operation{id='" + id + "', process=" + (process != null ? process.getId() : "null") + 
               ", quantity=" + quantity + ", standardHours=" + standardHours + 
               ", assignedLine=" + (assignedLine != null ? assignedLine.getCode() : "null") + 
               ", startTime=" + startTime + "}";
    }
}
