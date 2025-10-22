package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

public class LineShiftSlot {
    private Long id;
    private Line line;
    private LocalDate date;          // 生产日期
    private int startMinuteOfDay;    // 班段开始分钟（0..1439）
    private int endMinuteOfDay;      // 班段结束分钟（1..1440）
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

    // 用于“越早越好”软约束的时间权重（日期 + 日内分钟）
    public long getStartIndex() {
        return date.toEpochDay() * 24L * 60L + startMinuteOfDay;
    }

    public long getEndIndex() {
        return date.toEpochDay() * 24L * 60L + endMinuteOfDay;
    }

    @Override
    public String toString() {
        return line.getName() + "@" + date + " " + startMinuteOfDay + "-" + endMinuteOfDay;
    }
}