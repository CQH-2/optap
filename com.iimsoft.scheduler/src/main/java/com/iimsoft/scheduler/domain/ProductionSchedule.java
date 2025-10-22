package com.iimsoft.scheduler.domain;

import java.util.ArrayList;
import java.util.List;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.domain.solution.PlanningScore;

@PlanningSolution
public class ProductionSchedule {

    @ProblemFactCollectionProperty
    private List<Item> itemList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Line> lineList = new ArrayList<>();

    @ValueRangeProvider(id = "routerRange")
    @ProblemFactCollectionProperty
    private List<Router> routerList = new ArrayList<>();

    // 核心：每条产线“自己的”班次与日期生成的槽位集合
    @ValueRangeProvider(id = "slotRange")
    @ProblemFactCollectionProperty
    private List<LineShiftSlot> slotList = new ArrayList<>();

    // 为 indexInSlot 提供一个 0..(任务分片数-1) 的整型取值域
    @ValueRangeProvider(id = "indexRange")
    public List<Integer> getIndexRange() {
        int n = Math.max(1, taskPartList == null ? 0 : taskPartList.size());
        List<Integer> range = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            range.add(i);
        }
        return range;
    }

    // BOM 与需求（问题事实）
    @ProblemFactCollectionProperty
    private List<BomComponent> bomList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Demand> demandList = new ArrayList<>();

    // 如你的本地分支已加入库存/需求展开，请确保它们也非空
    @ProblemFactCollectionProperty
    private List<Inventory> inventoryList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Requirement> requirementList = new ArrayList<>();

    // 原始任务作为问题事实（用于统计与展示、拆分来源）
    @ProblemFactCollectionProperty
    private List<Task> taskList = new ArrayList<>();

    // 规划实体：任务分片
    @PlanningEntityCollectionProperty
    private List<TaskPart> taskPartList = new ArrayList<>();

    @PlanningScore
    private HardSoftScore score;

    public ProductionSchedule() {}

    public List<Item> getItemList() { return itemList; }
    public void setItemList(List<Item> itemList) { this.itemList = nn(itemList); }

    public List<Line> getLineList() { return lineList; }
    public void setLineList(List<Line> lineList) { this.lineList = nn(lineList); }

    public List<Router> getRouterList() { return routerList; }
    public void setRouterList(List<Router> routerList) { this.routerList = nn(routerList); }

    public List<LineShiftSlot> getSlotList() { return slotList; }
    public void setSlotList(List<LineShiftSlot> slotList) { this.slotList = nn(slotList); }

    public List<BomComponent> getBomList() { return bomList; }
    public void setBomList(List<BomComponent> bomList) { this.bomList = nn(bomList); }

    public List<Demand> getDemandList() { return demandList; }
    public void setDemandList(List<Demand> demandList) { this.demandList = nn(demandList); }

    public List<Inventory> getInventoryList() { return inventoryList; }
    public void setInventoryList(List<Inventory> inventoryList) { this.inventoryList = nn(inventoryList); }

    public List<Requirement> getRequirementList() { return requirementList; }
    public void setRequirementList(List<Requirement> requirementList) { this.requirementList = nn(requirementList); }

    public List<Task> getTaskList() { return taskList; }
    public void setTaskList(List<Task> taskList) { this.taskList = nn(taskList); }

    public List<TaskPart> getTaskPartList() { return taskPartList; }
    public void setTaskPartList(List<TaskPart> taskPartList) { this.taskPartList = nn(taskPartList); }

    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }

    private static <T> List<T> nn(List<T> in) {
        return in == null ? new ArrayList<>() : in;
    }
}