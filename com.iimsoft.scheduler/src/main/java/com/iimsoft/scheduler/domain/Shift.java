package com.iimsoft.scheduler.domain;

/**
 * 班次枚举
 * 用于区分白班和夜班，并定义各班次的成本倍数
 */
public enum Shift {
    /**
     * 白班：8:00-19:00，正常成本
     */
    DAY(8, 19, 1.0, "白班"),
    
    /**
     * 夜班：20:00-次日7:00，成本1.5倍
     * 注意：夜班跨越午夜，需要特殊处理
     */
    NIGHT(20, 31, 1.5, "夜班"); // 31 表示次日7点（20+12-1）
    
    private final int startHour;
    private final int endHour; // 对于夜班，>23 表示跨天
    private final double costMultiplier; // 成本倍数
    private final String displayName;
    
    Shift(int startHour, int endHour, double costMultiplier, String displayName) {
        this.startHour = startHour;
        this.endHour = endHour;
        this.costMultiplier = costMultiplier;
        this.displayName = displayName;
    }
    
    public int getStartHour() {
        return startHour;
    }
    
    public int getEndHour() {
        return endHour;
    }
    
    public double getCostMultiplier() {
        return costMultiplier;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * 获取班次的实际工作小时数
     */
    public int getWorkingHours() {
        if (this == NIGHT) {
            // 夜班：20-23(4h) + 0-7(8h) = 12h
            return 12;
        } else {
            // 白班：8-19 = 12h
            return endHour - startHour + 1;
        }
    }
    
    /**
     * 判断指定小时是否属于该班次
     */
    public boolean containsHour(int hour) {
        if (this == DAY) {
            return hour >= 8 && hour <= 19;
        } else {
            // 夜班：20-23 或 0-7
            return hour >= 20 || hour <= 7;
        }
    }
}
