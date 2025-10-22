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
    // 该产线在不同班次的产能（单位：件/小时×班时 或 直接“件数”）
    private Map<Shift, Integer> capacityByShift = new HashMap<>();

    public Line() {}
    public Line(Long id, String name) { this.id = id; this.name = name; }

    public boolean supports(Router router) {
        return supportedRouters.contains(router);
    }
    public int capacityOf(Shift shift) {
        return capacityByShift.getOrDefault(shift, 0);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Set<Router> getSupportedRouters() { return supportedRouters; }
    public void setSupportedRouters(Set<Router> supportedRouters) { this.supportedRouters = supportedRouters; }
    public Map<Shift, Integer> getCapacityByShift() { return capacityByShift; }
    public void setCapacityByShift(Map<Shift, Integer> capacityByShift) { this.capacityByShift = capacityByShift; }
}