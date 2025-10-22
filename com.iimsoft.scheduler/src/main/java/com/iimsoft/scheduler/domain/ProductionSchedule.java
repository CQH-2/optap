package com.iimsoft.scheduler.domain;

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
    private List<Item> itemList;

    @ProblemFactCollectionProperty
    private List<Line> lineList;

    @ValueRangeProvider(id = "routerRange")
    @ProblemFactCollectionProperty
    private List<Router> routerList;

    // 核心：每条产线“自己的”班次与日期生成的槽位集合
    @ValueRangeProvider(id = "slotRange")
    @ProblemFactCollectionProperty
    private List<LineShiftSlot> slotList;

    @PlanningEntityCollectionProperty
    private List<Task> taskList;

    @PlanningScore
    private HardSoftScore score;

    public ProductionSchedule() {}

    public List<Item> getItemList() { return itemList; }
    public void setItemList(List<Item> itemList) { this.itemList = itemList; }
    public List<Line> getLineList() { return lineList; }
    public void setLineList(List<Line> lineList) { this.lineList = lineList; }
    public List<Router> getRouterList() { return routerList; }
    public void setRouterList(List<Router> routerList) { this.routerList = routerList; }
    public List<LineShiftSlot> getSlotList() { return slotList; }
    public void setSlotList(List<LineShiftSlot> slotList) { this.slotList = slotList; }
    public List<Task> getTaskList() { return taskList; }
    public void setTaskList(List<Task> taskList) { this.taskList = taskList; }
    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }
}