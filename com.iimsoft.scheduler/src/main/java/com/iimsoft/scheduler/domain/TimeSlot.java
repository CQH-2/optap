package com.iimsoft.scheduler.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 以小时为粒度的时间槽（班次时段内）
 */
public class TimeSlot {
    private LocalDate date;
    private int hour; // 0-23
    private int index; // 连续索引，便于累计计算（跨天也递增）

    public TimeSlot() {}

    public TimeSlot(LocalDate date, int hour, int index) {
        this.date = date;
        this.hour = hour;
        this.index = index;
    }

    public LocalDate getDate() { return date; }
    public int getHour() { return hour; }
    public int getIndex() { return index; }

    public void setDate(LocalDate date) { this.date = date; }
    public void setHour(int hour) { this.hour = hour; }
    public void setIndex(int index) { this.index = index; }

    @Override
    public String toString() {
        return date + " " + String.format("%02d:00", hour) + " [#" + index + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimeSlot)) return false;
        TimeSlot timeSlot = (TimeSlot) o;
        return index == timeSlot.index;
    }

    @Override
    public int hashCode() {
        return Objects.hash(index);
    }
}