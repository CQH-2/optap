package com.iimsoft.schduler.calendar;

import java.util.ArrayList;
import java.util.List;

/**
 * 班次配置（支持夜班跨天）。
 *
 * 时间单位：小时，0..24 表示一天内小时整点。
 * 区间语义：[startHour, endHour)，左闭右开。
 *
 * 跨天规则：
 * - endHour > startHour：同一天内区间（例如 8-20）。
 * - endHour <= startHour：跨天区间（例如 20-8 表示 20:00 到次日 08:00）。
 *
 * breaks 同样支持跨天（通常不需要跨天休息，但这里也支持）。
 */
public class WorkCalendarConfig {

    private List<Shift> shifts = new ArrayList<>();

    public List<Shift> getShifts() {
        return shifts;
    }

    public void setShifts(List<Shift> shifts) {
        this.shifts = shifts;
    }

    public static class Shift {
        private int startHour; // 0..24
        private int endHour;   // 0..24 (<= startHour means跨天)
        private List<TimeRange> breaks = new ArrayList<>();

        public int getStartHour() {
            return startHour;
        }

        public void setStartHour(int startHour) {
            this.startHour = startHour;
        }

        public int getEndHour() {
            return endHour;
        }

        public void setEndHour(int endHour) {
            this.endHour = endHour;
        }

        public List<TimeRange> getBreaks() {
            return breaks;
        }

        public void setBreaks(List<TimeRange> breaks) {
            this.breaks = breaks;
        }
    }

    public static class TimeRange {
        private int startHour;
        private int endHour;

        public TimeRange() {
        }

        public TimeRange(int startHour, int endHour) {
            this.startHour = startHour;
            this.endHour = endHour;
        }

        public int getStartHour() {
            return startHour;
        }

        public void setStartHour(int startHour) {
            this.startHour = startHour;
        }

        public int getEndHour() {
            return endHour;
        }

        public void setEndHour(int endHour) {
            this.endHour = endHour;
        }
    }

    /**
     * 默认：白班 + 夜班
     *
     * 白班：08-20，休息 12-13、18-19
     * 夜班：20-08（跨天），默认无休息（你可通过 -Dwork.calendar 自定义夜班休息）
     */
    public static WorkCalendarConfig defaultDayAndNightShift() {
        WorkCalendarConfig cfg = new WorkCalendarConfig();

        Shift day = new Shift();
        day.setStartHour(8);
        day.setEndHour(20);
        day.getBreaks().add(new TimeRange(12, 13));
        day.getBreaks().add(new TimeRange(18, 19));
        cfg.getShifts().add(day);

        Shift night = new Shift();
        night.setStartHour(20);
        night.setEndHour(8); // 跨天
        cfg.getShifts().add(night);

        return cfg;
    }
}
