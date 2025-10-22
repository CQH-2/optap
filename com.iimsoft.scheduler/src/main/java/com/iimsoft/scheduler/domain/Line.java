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

    private Set<Router> supportedRouters = new HashSet<>();
    private Map<Shift, Integer> capacityByShift = new HashMap<>();

    // 不同工艺在本产线上的生产速率（每件所需分钟数）
    private Map<Router, Integer> minutesPerUnitByRouter = new HashMap<>();

    public Line() {}
    public Line(Long id, String name) { this.id = id; this.name = name; }

    public boolean supports(Router router) {
        return supportedRouters.contains(router) && minutesPerUnitByRouter.containsKey(router);
    }

    // 新增：产线是否支持某物料（存在支持该物料的工艺，且配置了速率）
    public boolean supportsItem(Item item) {
        if (item == null) return false;
        for (Map.Entry<Router, Integer> e : minutesPerUnitByRouter.entrySet()) {
            Router r = e.getKey();
            if (supportedRouters.contains(r) && r.supports(item)) {
                return true;
            }
        }
        return false;
    }

    // 新增：给定物料，取本产线所有可用工艺的最小分钟/件；无支持时返回大数
    public int getMinutesPerUnitForItem(Item item) {
        if (item == null) return Integer.MAX_VALUE / 4;
        int best = Integer.MAX_VALUE / 4;
        for (Map.Entry<Router, Integer> e : minutesPerUnitByRouter.entrySet()) {
            Router r = e.getKey();
            Integer mpu = e.getValue();
            if (mpu != null && supportedRouters.contains(r) && r.supports(item)) {
                if (mpu < best) best = mpu;
            }
        }
        return best;
    }

    public int capacityOf(Shift shift) {
        return capacityByShift.getOrDefault(shift, 0);
    }

    public int getMinutesPerUnit(Router router) {
        Integer v = minutesPerUnitByRouter.get(router);
        if (v == null) {
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