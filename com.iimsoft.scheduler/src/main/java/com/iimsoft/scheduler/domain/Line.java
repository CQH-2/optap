package com.iimsoft.scheduler.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.optaplanner.core.api.domain.lookup.PlanningId;

public class Line {
    @PlanningId
    private Long id;
    private String name;

    // 该产线支持哪些工艺
    private Set<Router> supportedRouters = new HashSet<>();
    // 该产线在不同班次的产能（单位：件/小时×班时 或 直接“件数”）——目前未直接使用
    private Map<Shift, Integer> capacityByShift = new HashMap<>();

    // 新增：不同工艺在本产线上的生产速率（每件所需分钟数）
    private Map<Router, Integer> minutesPerUnitByRouter = new HashMap<>();

    public Line() {}
    public Line(Long id, String name) { this.id = id; this.name = name; }

    // 支持关系：既在 supportedRouters 集合里，并且配置了速率
    public boolean supports(Router router) {
        return supportedRouters.contains(router) && minutesPerUnitByRouter.containsKey(router);
    }

    public int capacityOf(Shift shift) {
        return capacityByShift.getOrDefault(shift, 0);
    }

    public int getMinutesPerUnit(Router router) {
        Integer v = minutesPerUnitByRouter.get(router);
        if (v == null) {
            // 未配置速率时，视为不支持（与 supports 一致），这里返回一个大数避免除零
            return Integer.MAX_VALUE / 4;
        }
        return v;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Set<Router> getSupportedRouters() { return supportedRouters; }
    public void setSupportedRouters(Set<Router> supportedRouters) { this.supportedRouters = supportedRouters; }
    public Map<Shift, Integer> getCapacityByShift() { return capacityByShift; }
    public void setCapacityByShift(Map<Shift, Integer> capacityByShift) { this.capacityByShift = capacityByShift; }

    public Map<Router, Integer> getMinutesPerUnitByRouter() { return minutesPerUnitByRouter; }
    public void setMinutesPerUnitByRouter(Map<Router, Integer> minutesPerUnitByRouter) {
        this.minutesPerUnitByRouter = minutesPerUnitByRouter;
    }
}