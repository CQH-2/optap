package com.iimsoft.scheduler.util;

import com.iimsoft.scheduler.domain.Line;
import com.iimsoft.scheduler.domain.LineHourSlot;
import com.iimsoft.scheduler.domain.Requirement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 小时槽位生成工具。
 */
public class HourSlotGenerator {

    /**
     * 基于“估算总工时 + 缓冲天数”的倒推窗口（已存在的方法）。
     */
    public static List<LineHourSlot> buildBackwardSlots(
            List<Line> lines,
            List<Requirement> requirements,
            WorkingHours workingHours,
            int bufferDays,
            long startingIdInclusive
    ) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>();
        }
        if (requirements == null || requirements.isEmpty()) {
            LocalDate today = LocalDate.now();
            return buildWindow(lines, today, today, workingHours, startingIdInclusive);
        }

        LocalDate endDate = requirements.stream()
                .map(Requirement::getDueDate)
                .max(Comparator.naturalOrder())
                .orElse(LocalDate.now());

        // 估算所需总分钟数（仅统计至少有一条产线可生产的物料）
        long totalMinutesNeeded = 0L;
        for (Requirement r : requirements) {
            if (r == null || r.getItem() == null || r.getQuantity() <= 0) continue;
            int bestMpu = Integer.MAX_VALUE / 4;
            for (Line line : lines) {
                if (line != null && line.supportsItem(r.getItem())) {
                    int mpu = line.getMinutesPerUnitForItem(r.getItem());
                    if (mpu < bestMpu) bestMpu = mpu;
                }
            }
            if (bestMpu < Integer.MAX_VALUE / 8) {
                totalMinutesNeeded += (long) bestMpu * (long) r.getQuantity();
            }
        }

        int minutesPerDayPerLine = workingHours.getMinutesPerDay();
        long fleetMinutesPerDay = (long) lines.size() * (long) minutesPerDayPerLine;

        long daysNeeded = (totalMinutesNeeded <= 0 || fleetMinutesPerDay <= 0)
                ? 1
                : (long) Math.ceil((double) totalMinutesNeeded / (double) fleetMinutesPerDay);

        long minDays = 1;
        long maxDays = 60;
        long windowDays = Math.min(Math.max(daysNeeded + Math.max(bufferDays, 0), minDays), maxDays);

        LocalDate startDate = endDate.minusDays(windowDays - 1);
        return buildWindow(lines, startDate, endDate, workingHours, startingIdInclusive);
    }

    /**
     * 新增：固定“倒退 N 天”的窗口。以所有需求的最大交期为窗口结束，向前固定 windowDays 天。
     * 例如 windowDays=2，则生成 [endDate-1, endDate] 两天的槽位。
     */
    public static List<LineHourSlot> buildBackwardSlotsForDays(
            List<Line> lines,
            List<Requirement> requirements,
            WorkingHours workingHours,
            int windowDays,
            long startingIdInclusive
    ) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>();
        }
        // 窗口结束日：最大交期；没有需求则用今天
        LocalDate endDate = (requirements == null || requirements.isEmpty())
                ? LocalDate.now()
                : requirements.stream()
                .map(Requirement::getDueDate)
                .max(Comparator.naturalOrder())
                .orElse(LocalDate.now());

        int wd = Math.max(1, windowDays);
        LocalDate startDate = endDate.minusDays(wd - 1);
        return buildWindow(lines, startDate, endDate, workingHours, startingIdInclusive);
    }

    private static List<LineHourSlot> buildWindow(
            List<Line> lines,
            LocalDate startDate,
            LocalDate endDate,
            WorkingHours workingHours,
            long startingIdInclusive
    ) {
        List<LineHourSlot> slots = new ArrayList<>();
        long id = startingIdInclusive <= 0 ? 1L : startingIdInclusive;

        // 倒序按日期、小时生成（从 endDate 当天最后一小时往前）
        for (LocalDate d = endDate; !d.isBefore(startDate); d = d.minusDays(1)) {
            for (Line line : lines) {
                for (int h = workingHours.getEndHourExclusive() - 1; h >= workingHours.getStartHourInclusive(); h--) {
                    slots.add(new LineHourSlot(id++, line, d, h));
                }
            }
        }
        return slots;
    }
}