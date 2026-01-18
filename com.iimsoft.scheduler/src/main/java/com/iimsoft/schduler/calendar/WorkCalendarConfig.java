package com.iimsoft.schduler.calendar;

import java.util.ArrayList;
import java.util.List;

/**
 * 工厂统一班次配置（每天班次模板一样）+ 可配置哪些日期开工。
 *
 * 时���单位：小时，0..24 表示一天内小时整点。
 * 区间语义：[startHour, endHour)，左闭右开。
 *
 * 跨天规则：
 * - endHour > startHour：同一天内区间（例如 8-20）。
 * - endHour <= startHour：跨天区间（例如 20-8 表示 20:00 到次日 08:00）。
 *
 * workDates：
 * - 日期格式：YYYY-MM-DD
 * - 语义（按你的最新要求）：workDates 为空/缺省 => 不工作（全部日期停工）。
 */
public class WorkCalendarConfig {

    private List shifts = new ArrayList<>();

    /** 允许工作的日期列表（YYYY-MM-DD）。为空/缺省 => 不工作 */
    private List<String> workDates = new ArrayList<>();

    public List getShifts() {
        return shifts;
    }

    public void setShifts(List shifts) {
        this.shifts = shifts;
    }

    public List<String> getWorkDates() {
        return workDates;
    }

    public void setWorkDates(List<String> workDates) {
        this.workDates = workDates;
    }

    public static class Shift {
        private int startHour; // 0..24
        private int endHour;   // 0..24 (<= startHour means跨天)
        private List breaks = new ArrayList<>();

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

        public List getBreaks() {
            return breaks;
        }

        public void setBreaks(List breaks) {
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
}