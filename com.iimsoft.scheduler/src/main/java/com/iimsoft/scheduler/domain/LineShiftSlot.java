package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

public class LineShiftSlot implements Standstill {
    private Long id;
    private Line line;
    private LocalDate date;          // 班次开始所在的生产日期
    private int startMinuteOfDay;    // 班段开始分钟（0..1439）
    private int endMinuteOfDay;      // 班段结束分钟（1..1440）。若跨天，end < start 表示次日的该分钟
    private int capacityUnits;       // 本班段可生产的单位数（与 Task.quantity 单位一致）

    public LineShiftSlot() {}

    public LineShiftSlot(Long id, Line line, LocalDate date, int startMinuteOfDay, int endMinuteOfDay, int capacityUnits) {
        this.id = id;
        this.line = line;
        this.date = date;
        this.startMinuteOfDay = startMinuteOfDay;
        this.endMinuteOfDay = endMinuteOfDay;
        this.capacityUnits = capacityUnits;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Line getLine() { return line; }
    public void setLine(Line line) { this.line = line; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public int getStartMinuteOfDay() { return startMinuteOfDay; }
    public void setStartMinuteOfDay(int startMinuteOfDay) { this.startMinuteOfDay = startMinuteOfDay; }
    public int getEndMinuteOfDay() { return endMinuteOfDay; }
    public void setEndMinuteOfDay(int endMinuteOfDay) { this.endMinuteOfDay = endMinuteOfDay; }
    public int getCapacityUnits() { return capacityUnits; }
    public void setCapacityUnits(int capacityUnits) { this.capacityUnits = capacityUnits; }

    // 是否跨天（结束分钟小于开始分钟则视为跨天到次日）
    public boolean isCrossDay() {
        return endMinuteOfDay < startMinuteOfDay;
    }

    // 槽位时长（分钟）。跨天时 = (24h - start) + end
    public int getDurationMinutes() {
        int d = endMinuteOfDay - startMinuteOfDay;
        return d >= 0 ? d : d + 24 * 60;
    }

    // 时间轴起点：以“date 的 00:00”为基准的分钟索引
    public long getStartIndex() {
        return date.toEpochDay() * 24L * 60L + startMinuteOfDay;
    }

    // 时间轴终点：跨天则推进到“次日”的分钟索引
    public long getEndIndex() {
        long base = date.toEpochDay() * 24L * 60L;
        if (isCrossDay()) {
            return base + 24L * 60L + endMinuteOfDay; // 次日 endMinuteOfDay
        } else {
            return base + endMinuteOfDay;
        }
    }

    // Standstill 接口实现：链头的“前件结束时间”等于槽位开始
    @Override
    public LineShiftSlot getSlot() {
        return this;
    }

    @Override
    public Long getChainEndIndex() {
        return getStartIndex();
    }

    @Override
    public String toString() {
        String cross = isCrossDay() ? " (跨天)" : "";
        return line.getName() + "@" + date + " " + startMinuteOfDay + "-" + endMinuteOfDay + cross;
    }
}