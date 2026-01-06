package com.iimsoft.scheduler.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 工艺：只能生产一个物料，速度：件/小时
 * 新增：setupTimeHours 换线时间（小时）、minBatchSize 最小批量、predecessors 前置工序
 */
public class Router {
    private String code;
    private Item item;
    private int speedPerHour;
    private int setupTimeHours; // 换线时间（小时），默认0
    private int minBatchSize;   // 最小起订量，默认0（无限制）
    private List<Router> predecessors; // 前置工序列表（必须在本工序之前完成）

    public Router() {
        this.predecessors = new ArrayList<>();
    }

    public Router(String code, Item item, int speedPerHour) {
        this(code, item, speedPerHour, 0, 0);
    }
    
    public Router(String code, Item item, int speedPerHour, int setupTimeHours) {
        this(code, item, speedPerHour, setupTimeHours, 0);
    }
    
    public Router(String code, Item item, int speedPerHour, int setupTimeHours, int minBatchSize) {
        this.code = code;
        this.item = item;
        this.speedPerHour = speedPerHour;
        this.setupTimeHours = setupTimeHours;
        this.minBatchSize = minBatchSize;
        this.predecessors = new ArrayList<>();
    }

    public String getCode() { return code; }
    public Item getItem() { return item; }
    public int getSpeedPerHour() { return speedPerHour; }
    public int getSetupTimeHours() { return setupTimeHours; }
    public int getMinBatchSize() { return minBatchSize; }
    public List<Router> getPredecessors() { return predecessors; }

    public void setCode(String code) { this.code = code; }
    public void setItem(Item item) { this.item = item; }
    public void setSpeedPerHour(int speedPerHour) { this.speedPerHour = speedPerHour; }
    public void setSetupTimeHours(int setupTimeHours) { this.setupTimeHours = setupTimeHours; }
    public void setMinBatchSize(int minBatchSize) { this.minBatchSize = minBatchSize; }
    public void setPredecessors(List<Router> predecessors) { this.predecessors = predecessors; }
    
    /**
     * 添加前置工序
     */
    public void addPredecessor(Router predecessor) {
        if (predecessor != null && !this.predecessors.contains(predecessor)) {
            this.predecessors.add(predecessor);
        }
    }
    
    /**
     * 检查是否有前置工序要求
     */
    public boolean hasPredecessors() {
        return predecessors != null && !predecessors.isEmpty();
    }

    @Override
    public String toString() {
        return code + "(" + item + "@" + speedPerHour + "/h)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Router)) return false;
        Router router = (Router) o;
        return Objects.equals(code, router.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}