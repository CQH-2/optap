package com.iimsoft.scheduler.domain;

import java.util.List;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;

import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

@PlanningSolution
public class ProductionSchedule {

    // 值域与事实
    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "routerRange")
    private List<Router> routerList;

    @ProblemFactCollectionProperty
    private List<ProductionLine> lineList;

    @ProblemFactCollectionProperty
    private List<TimeSlot> timeSlotList;

    @ProblemFactCollectionProperty
    private List<BomArc> bomArcList;

    // 新增：库存事实（初始在库）
    @ProblemFactCollectionProperty
    private List<ItemInventory> inventoryList;

    @ProblemFactCollectionProperty
    private List<DemandOrder> demandList;

    // 规划实体：每条产线每个时段的格子
    @PlanningEntityCollectionProperty
    private List<ProductionAssignment> assignmentList;

    @PlanningScore
    private HardSoftScore score;

    public ProductionSchedule() {}

    public ProductionSchedule(List<Router> routerList,
                              List<ProductionLine> lineList,
                              List<TimeSlot> timeSlotList,
                              List<BomArc> bomArcList,
                              List<ItemInventory> inventoryList,
                              List<DemandOrder> demandList,
                              List<ProductionAssignment> assignmentList) {
        this.routerList = routerList;
        this.lineList = lineList;
        this.timeSlotList = timeSlotList;
        this.bomArcList = bomArcList;
        this.inventoryList = inventoryList;
        this.demandList = demandList;
        this.assignmentList = assignmentList;
    }

    public List<Router> getRouterList() { return routerList; }
    public List<ProductionLine> getLineList() { return lineList; }
    public List<TimeSlot> getTimeSlotList() { return timeSlotList; }
    public List<BomArc> getBomArcList() { return bomArcList; }
    public List<ItemInventory> getInventoryList() { return inventoryList; }
    public List<DemandOrder> getDemandList() { return demandList; }
    public List<ProductionAssignment> getAssignmentList() { return assignmentList; }
    public HardSoftScore getScore() { return score; }

    public void setRouterList(List<Router> routerList) { this.routerList = routerList; }
    public void setLineList(List<ProductionLine> lineList) { this.lineList = lineList; }
    public void setTimeSlotList(List<TimeSlot> timeSlotList) { this.timeSlotList = timeSlotList; }
    public void setBomArcList(List<BomArc> bomArcList) { this.bomArcList = bomArcList; }
    public void setInventoryList(List<ItemInventory> inventoryList) { this.inventoryList = inventoryList; }
    public void setDemandList(List<DemandOrder> demandList) { this.demandList = demandList; }
    public void setAssignmentList(List<ProductionAssignment> assignmentList) { this.assignmentList = assignmentList; }
    public void setScore(HardSoftScore score) { this.score = score; }
}