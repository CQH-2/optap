package com.iimsoft.schduler.api.dto;

import java.util.List;

public class SolveResponse {

    public String score;

    /** 每个 job 的排程结果（含日期时间） */
    public List<AllocationResult> allocations;

    /** 每个资源每小时的占用（用于画“网格/负荷”） */
    public List<ResourceUsage> resourceUsages;

    /** 库存时间线（事件 + 余额曲线抽样） */
    public List<ItemInventoryTimeline> inventoryTimelines;

    public static class AllocationResult {
        public long projectId;
        public long jobId;
        public String jobType;

        public Long startHour;
        public Long endHour;

        public String startDateTime; // ISO-8601
        public String endDateTime;

        public Integer durationCalendarHours; // end-start（包含休息/停工）
        public Integer durationWorkHours;     // executionMode.duration（有效工时）
    }

    public static class ResourceUsage {
        public long resourceId;
        public int hour;
        public String dateTime;
        public int used;
        public int capacity;
    }

    public static class ItemInventoryTimeline {
        public long itemId;
        public String itemCode;

        /** 事件明细 */
        public List<InventoryEventAtTime> events;

        /** 余额曲线（可按需求抽样，否则太长） */
        public List<InventoryBalancePoint> balancePoints;
    }

    public static class InventoryEventAtTime {
        public int hour;
        public String dateTime;
        public int quantity;
        public String timePolicy; // START/END
        public long jobId;
    }

    public static class InventoryBalancePoint {
        public int hour;
        public String dateTime;
        public int balance;
    }
}