package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.lookup.PlanningId;

public class Shift {
    @PlanningId
    private Long id;
    private String name;
    // 越小越早，用于软约束“优先早班”
    private int index;

    public Shift() {}
    public Shift(Long id, String name, int index) {
        this.id = id; this.name = name; this.index = index;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
}