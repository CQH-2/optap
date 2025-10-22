package com.iimsoft.scheduler.domain;

import org.optaplanner.core.api.domain.lookup.PlanningId;

public class Item {
    @PlanningId
    private Long id;
    private String code;
    private String name;

    // 新增：安全库存（目标库存），单位需与 Task/TaskPart 数量一致
    private int safetyStock;

    public Item() {}
    public Item(Long id, String code, String name) {
        this.id = id; this.code = code; this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getSafetyStock() { return safetyStock; }
    public void setSafetyStock(int safetyStock) { this.safetyStock = safetyStock; }
}