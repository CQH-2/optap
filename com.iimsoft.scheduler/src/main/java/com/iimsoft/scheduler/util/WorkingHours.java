package com.iimsoft.scheduler.util;

/**
 * 简单工作时间配置：每天从 startHourInclusive 到 endHourExclusive（整点），例如 8..17 表示 8:00-17:00 共 9 小时。
 */
public class WorkingHours {
    private final int startHourInclusive;
    private final int endHourExclusive;

    public WorkingHours(int startHourInclusive, int endHourExclusive) {
        if (startHourInclusive < 0 || startHourInclusive > 23
                || endHourExclusive < 1 || endHourExclusive > 24
                || endHourExclusive <= startHourInclusive) {
            throw new IllegalArgumentException("Invalid working hours range: " + startHourInclusive + ".." + endHourExclusive);
        }
        this.startHourInclusive = startHourInclusive;
        this.endHourExclusive = endHourExclusive;
    }

    public int getStartHourInclusive() {
        return startHourInclusive;
    }

    public int getEndHourExclusive() {
        return endHourExclusive;
    }

    public int getHoursPerDay() {
        return endHourExclusive - startHourInclusive;
    }

    public int getMinutesPerDay() {
        return getHoursPerDay() * 60;
    }
}