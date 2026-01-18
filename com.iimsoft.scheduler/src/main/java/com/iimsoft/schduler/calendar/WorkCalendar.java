package com.iimsoft.schduler.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.BitSet;

/**
 * 工作日历（按小时判断某个 absoluteHour 是否可工作）。
 *
 * 配置来源（优先级从高到低）：
 * 1) JVM 参数：-Dwork.calendar=JSON
 * 2) 默认：白班+夜班（见 WorkCalendarConfig.defaultDayAndNightShift()）
 *
 * 说明：
 * - 这里把“班次生成 slot”统一抽象为：一天 24 小时哪些小时可工作（BitSet）。
 * - Allocation 和评分器都只调用 WorkCalendar.isWorkingHour(hour)，避免重复规则。
 */
public final class WorkCalendar {

    /** JVM 参数 key */
    public static final String WORK_CALENDAR_JSON_PROPERTY = "work.calendar";

    /** 一天内 0..23 哪些小时可工作 */
    private static volatile BitSet workingHoursOfDay;

    private WorkCalendar() {
    }

    public static boolean isWorkingHour(int absoluteHour) {
        BitSet bs = getWorkingHoursOfDay();
        int hod = Math.floorMod(absoluteHour, 24);
        return bs.get(hod);
    }

    /** 如果运行中你改了 System properties，可调用它刷新（一般不需要）。 */
    public static void reload() {
        workingHoursOfDay = null;
    }

    private static BitSet getWorkingHoursOfDay() {
        BitSet local = workingHoursOfDay;
        if (local != null) {
            return local;
        }
        synchronized (WorkCalendar.class) {
            if (workingHoursOfDay == null) {
                WorkCalendarConfig cfg = loadConfig();
                workingHoursOfDay = buildWorkingHoursOfDay(cfg);
            }
            return workingHoursOfDay;
        }
    }

    private static WorkCalendarConfig loadConfig() {
        String json = System.getProperty(WORK_CALENDAR_JSON_PROPERTY);
        if (json == null || json.isBlank()) {
            return WorkCalendarConfig.defaultDayAndNightShift();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, WorkCalendarConfig.class);
        } catch (Exception e) {
            // 配置错误回退默认，避免程序直接挂
            return WorkCalendarConfig.defaultDayAndNightShift();
        }
    }

    /**
     * 生成一天内 24 小时的可工作表。
     *
     * 规则：
     * - 所有 shift 覆盖范围设置为可工作（支持跨天：end<=start）
     * - shift 内 breaks 覆盖范围设置为不可工作（同样支持跨天）
     */
    private static BitSet buildWorkingHoursOfDay(WorkCalendarConfig cfg) {
        BitSet bs = new BitSet(24);

        if (cfg.getShifts() == null || cfg.getShifts().isEmpty()) {
            return bs; // 全不可工作
        }

        for (WorkCalendarConfig.Shift s : cfg.getShifts()) {
            markRange(bs, s.getStartHour(), s.getEndHour(), true);
            if (s.getBreaks() != null) {
                for (WorkCalendarConfig.TimeRange br : s.getBreaks()) {
                    markRange(bs, br.getStartHour(), br.getEndHour(), false);
                }
            }
        }
        return bs;
    }

    /**
     * 支持跨天区间：
     * - end > start ：同一天
     * - end <= start：跨天（start..24 + 0..end）
     */
    private static void markRange(BitSet bs, int startHour, int endHour, boolean value) {
        int start = clampHour(startHour);
        int end = clampHour(endHour);

        if (start == end) {
            return;
        }

        if (end > start) {
            bs.set(start, end, value);
        } else {
            bs.set(start, 24, value);
            bs.set(0, end, value);
        }
    }

    private static int clampHour(int h) {
        if (h < 0) return 0;
        if (h > 24) return 24;
        return h;
    }
}
