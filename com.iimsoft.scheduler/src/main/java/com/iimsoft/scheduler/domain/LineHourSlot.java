package com.iimsoft.scheduler.domain;

import java.time.LocalDate;

public class LineHourSlot {
    private Long id;
    private Line line;
    private LocalDate date;
    private int hourOfDay; // 0..23

    public LineHourSlot() {}

    public LineHourSlot(Long id, Line line, LocalDate date, int hourOfDay) {
        this.id = id;
        this.line = line;
        this.date = date;
        this.hourOfDay = hourOfDay;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Line getLine() { return line; }
    public void setLine(Line line) { this.line = line; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getHourOfDay() { return hourOfDay; }
    public void setHourOfDay(int hourOfDay) { this.hourOfDay = hourOfDay; }

    public int getStartMinuteOfDay() { return hourOfDay * 60; }
    public int getEndMinuteOfDay() { return (hourOfDay + 1) * 60; }
    public int getDurationMinutes() { return 60; }

    public long getStartIndex() {
        return date.toEpochDay() * 24L * 60L + getStartMinuteOfDay();
    }

    public long getEndIndex() {
        return date.toEpochDay() * 24L * 60L + getEndMinuteOfDay();
    }

    @Override
    public String toString() {
        return line.getName() + "@" + date + " " + String.format("%02d:00-%02d:00", hourOfDay, hourOfDay + 1);
    }
}