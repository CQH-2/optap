package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.CountableValueRange;
import org.optaplanner.core.api.domain.valuerange.ValueRangeFactory;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

import java.util.List;

/**
 * 生产排程解决方案：定义问题的输入和输出
 */
@PlanningSolution
public class SchedulingSolution {

    // 问题事实 - 资源和约束
    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "lineRange")
    private List<ProductionLine> lineList;

    @ProblemFactCollectionProperty
    private List<Process> processList;

    @ProblemFactCollectionProperty
    private List<Material> materialList;

    @ProblemFactCollectionProperty
    private List<Order> orderList;

    @ProblemFactCollectionProperty
    private List<BOMItem> bomItemList;

    @ProblemFactCollectionProperty
    private List<Inventory> inventoryList;

    @ProblemFactCollectionProperty
    private List<LineProcessCapacity> lineProcessCapacityList;

    // 规划实体 - 要优化的工序
    @PlanningEntityCollectionProperty
    private List<Operation> operationList;

    // 时间范围（用于startTime规划变量）
    private Long planningWindowStart; // 规划窗口起始时间戳
    private Long planningWindowEnd;   // 规划窗口结束时间戳

    // 分数
    @PlanningScore
    private HardSoftScore score;

    public SchedulingSolution() {}

    public SchedulingSolution(List<ProductionLine> lineList, 
                             List<Process> processList,
                             List<Material> materialList,
                             List<Order> orderList,
                             List<BOMItem> bomItemList,
                             List<Inventory> inventoryList,
                             List<LineProcessCapacity> lineProcessCapacityList,
                             List<Operation> operationList,
                             Long planningWindowStart,
                             Long planningWindowEnd) {
        this.lineList = lineList;
        this.processList = processList;
        this.materialList = materialList;
        this.orderList = orderList;
        this.bomItemList = bomItemList;
        this.inventoryList = inventoryList;
        this.lineProcessCapacityList = lineProcessCapacityList;
        this.operationList = operationList;
        this.planningWindowStart = planningWindowStart;
        this.planningWindowEnd = planningWindowEnd;
    }

    /**
     * 提供时间范围的值域
     */
    @ValueRangeProvider(id = "timeRange")
    public CountableValueRange<Long> getTimeRange() {
        if (planningWindowStart == null || planningWindowEnd == null) {
            return ValueRangeFactory.createLongValueRange(0L, 1L);
        }
        // 每小时一个时间点
        return ValueRangeFactory.createLongValueRange(
            planningWindowStart, 
            planningWindowEnd, 
            3600 * 1000 // 1小时步长（毫秒）
        );
    }

    // Getters and Setters
    public List<ProductionLine> getLineList() { return lineList; }
    public List<Process> getProcessList() { return processList; }
    public List<Material> getMaterialList() { return materialList; }
    public List<Order> getOrderList() { return orderList; }
    public List<BOMItem> getBomItemList() { return bomItemList; }
    public List<Inventory> getInventoryList() { return inventoryList; }
    public List<LineProcessCapacity> getLineProcessCapacityList() { return lineProcessCapacityList; }
    public List<Operation> getOperationList() { return operationList; }
    public Long getPlanningWindowStart() { return planningWindowStart; }
    public Long getPlanningWindowEnd() { return planningWindowEnd; }
    public HardSoftScore getScore() { return score; }

    public void setLineList(List<ProductionLine> lineList) { this.lineList = lineList; }
    public void setProcessList(List<Process> processList) { this.processList = processList; }
    public void setMaterialList(List<Material> materialList) { this.materialList = materialList; }
    public void setOrderList(List<Order> orderList) { this.orderList = orderList; }
    public void setBomItemList(List<BOMItem> bomItemList) { this.bomItemList = bomItemList; }
    public void setInventoryList(List<Inventory> inventoryList) { this.inventoryList = inventoryList; }
    public void setLineProcessCapacityList(List<LineProcessCapacity> lineProcessCapacityList) { 
        this.lineProcessCapacityList = lineProcessCapacityList; 
    }
    public void setOperationList(List<Operation> operationList) { this.operationList = operationList; }
    public void setPlanningWindowStart(Long planningWindowStart) { this.planningWindowStart = planningWindowStart; }
    public void setPlanningWindowEnd(Long planningWindowEnd) { this.planningWindowEnd = planningWindowEnd; }
    public void setScore(HardSoftScore score) { this.score = score; }
}
