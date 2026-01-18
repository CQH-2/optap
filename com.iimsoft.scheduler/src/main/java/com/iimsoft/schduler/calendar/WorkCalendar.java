package com.iimsoft.schduler.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iimsoft.schduler.api.dto.SolveRequest;

import java.time.LocalDate;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 工作日历（按小时判断某个 absoluteHour 是否可工作）。
 *
 * 现状：原先通过 JVM 参数 -Dwork.calendar=JSON。
 * 为了服务化：增加 loadFromRequestCalendar(...)，允许每次请求注入日历（注意：静态全局，非并发安全）。
 */
public final class WorkCalendar {

    public static final String WORK_CALENDAR_JSON_PROPERTY = "work.calendar";

    private static volatile BitSet workingHoursOfDay;
    private static volatile Set<LocalDate> workDates;
    private static volatile LocalDate timelineStartDate;

    private WorkCalendar() {
    }

    public static boolean isWorkingHour(int absoluteHour) {
        BitSet bs = workingHoursOfDay;
        Set<LocalDate> dates = workDates;
        LocalDate start = timelineStartDate;
        if (bs == null || dates == null || start == null) {
            return false;
        }
        if (dates.isEmpty()) {
            // 你要求：workDates 为空时不工作
            return false;
        }
        int hod = Math.floorMod(absoluteHour, 24);
        if (!bs.get(hod)) {
            return false;
        }
        int dayIndex = Math.floorDiv(absoluteHour, 24);
        LocalDate date = start.plusDays(dayIndex);
        return dates.contains(date);
    }

    /** 供服务层调用：从请求注入日历（静态全局，单线程使用没问题） */
    public static void loadFromRequestCalendar(String timelineStartDateStr,
                                               java.util.List<SolveRequest.ShiftDto> shifts,
                                               java.util.List<String> workDateStrings) {
        LocalDate start = LocalDate.parse(timelineStartDateStr);

        BitSet bs = new BitSet(24);
        if (shifts != null) {
            for (SolveRequest.ShiftDto s : shifts) {
                markRange(bs, s.startHour, s.endHour, true);
                if (s.breaks != null) {
                    for (SolveRequest.TimeRangeDto br : s.breaks) {
                        markRange(bs, br.startHour, br.endHour, false);
                    }
                }
            }
        }

        Set<LocalDate> dates = new HashSet<>();
        if (workDateStrings != null) {
            for (String d : workDateStrings) {
                if (d == null || d.isBlank()) continue;
                dates.add(LocalDate.parse(d.trim()));
            }
        }

        // 原子替换缓存
        WorkCalendar.timelineStartDate = start;
        WorkCalendar.workingHoursOfDay = bs;
        WorkCalendar.workDates = dates;
    }




    private static BitSet buildWorkingHoursOfDay(WorkCalendarConfig cfg) {
        BitSet bs = new BitSet(24);
        if (cfg.getShifts() == null || cfg.getShifts().isEmpty()) {
            return bs;
        }
        for (Object o : cfg.getShifts()) {
            WorkCalendarConfig.Shift s = (WorkCalendarConfig.Shift) o;
            markRange(bs, s.getStartHour(), s.getEndHour(), true);
            if (s.getBreaks() != null) {
                for (Object brObj : s.getBreaks()) {
                    WorkCalendarConfig.TimeRange br = (WorkCalendarConfig.TimeRange) brObj;
                    markRange(bs, br.getStartHour(), br.getEndHour(), false);
                }
            }
        }
        return bs;
    }

    private static void markRange(BitSet bs, int startHour, int endHour, boolean value) {
        int start = clampHour(startHour);
        int end = clampHour(endHour);
        if (start == end) return;

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