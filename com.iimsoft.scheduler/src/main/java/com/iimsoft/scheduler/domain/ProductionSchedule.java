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

    // 物料集合作为取值域
    @ValueRangeProvider(id = "itemRange")
    @ProblemFactCollectionProperty
    private List<Item> itemList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Line> lineList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Router> routerList = new ArrayList<>();

    // 每小时的产线时隙（问题事实）
    @ProblemFactCollectionProperty
    private List<LineHourSlot> hourSlotList = new ArrayList<>();

    // 需求、BOM、库存
    @ProblemFactCollectionProperty
    private List<BomComponent> bomList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Demand> demandList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Inventory> inventoryList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Requirement> requirementList = new ArrayList<>();

    // 规划实体：每小时的生产分配（变量为 item、quantity）
    @PlanningEntityCollectionProperty
    private List<HourPlan> planList = new ArrayList<>();

    // 数量取值域（0..maxQuantityPerHour）
    private int maxQuantityPerHour = 200;

    @ValueRangeProvider(id = "quantityRange")
    public List<Integer> getQuantityRange() {
        int max = Math.max(0, maxQuantityPerHour);
        List<Integer> range = new ArrayList<>(max + 1);
        for (int i = 0; i <= max; i++) {
            range.add(i);
        }
        return range;
    }

    @PlanningScore
    private HardSoftScore score;

    public ProductionSchedule() {}

    public List<Item> getItemList() { return itemList; }
    public void setItemList(List<Item> itemList) { this.itemList = nn(itemList); }

    public List<Line> getLineList() { return lineList; }
    public void setLineList(List<Line> lineList) { this.lineList = nn(lineList); }

    public List<Router> getRouterList() { return routerList; }
    public void setRouterList(List<Router> routerList) { this.routerList = nn(routerList); }

    public List<LineHourSlot> getHourSlotList() { return hourSlotList; }
    public void setHourSlotList(List<LineHourSlot> hourSlotList) { this.hourSlotList = nn(hourSlotList); }

    public List<BomComponent> getBomList() { return bomList; }
    public void setBomList(List<BomComponent> bomList) { this.bomList = nn(bomList); }

    public List<Demand> getDemandList() { return demandList; }
    public void setDemandList(List<Demand> demandList) { this.demandList = nn(demandList); }

    public List<Inventory> getInventoryList() { return inventoryList; }
    public void setInventoryList(List<Inventory> inventoryList) { this.inventoryList = nn(inventoryList); }

    public List<Requirement> getRequirementList() { return requirementList; }
    public void setRequirementList(List<Requirement> requirementList) { this.requirementList = nn(requirementList); }

    public List<HourPlan> getPlanList() { return planList; }
    public void setPlanList(List<HourPlan> planList) { this.planList = nn(planList); }

    public int getMaxQuantityPerHour() { return maxQuantityPerHour; }
    public void setMaxQuantityPerHour(int maxQuantityPerHour) { this.maxQuantityPerHour = maxQuantityPerHour; }

    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }

    private static <T> List<T> nn(List<T> in) {
        return in == null ? new ArrayList<>() : in;
    }
}