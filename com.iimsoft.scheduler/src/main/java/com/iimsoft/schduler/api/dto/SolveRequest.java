package com.iimsoft.schduler.api.dto;

import java.util.List;

public class SolveRequest {

    public CalendarDto calendar;

    public List<ProjectDto> projects;
    public List<JobDto> jobs;

    public List<ItemDto> items;
    public List<InventoryEventDto> inventoryEvents;

    public List<ResourceDto> resources;
    public List<ResourceRequirementDto> resourceRequirements;

    /** 可选：求解时长 */
    public Integer terminationSeconds = 10;

    public static class CalendarDto {
        /** 时间轴 hour=0 对应的日期 */
        public String timelineStartDate; // YYYY-MM-DD
        public List<ShiftDto> shifts;
        public List<String> workDates; // YYYY-MM-DD
    }

    public static class ShiftDto {
        public int startHour;
        public int endHour;
        public List<TimeRangeDto> breaks;
    }

    public static class TimeRangeDto {
        public int startHour;
        public int endHour;
    }

    public static class ProjectDto {
        public long id;
        public int releaseHour;
        public int criticalPathHours;
    }

    public static class JobDto {
        public long id;
        public long projectId;
        public String jobType; // SOURCE/STANDARD/SINK
        public int durationWorkHours;
        public List<Long> successorJobIds;
    }

    public static class ItemDto {
        public long id;
        public String code;
        public int initialStock;
    }

    public static class InventoryEventDto {
        public long id;
        public long jobId;      // 绑定到 jobId（比 allocationId 更稳定）
        public long itemId;
        public int quantity;
        public String timePolicy; // START/END
    }

    public static class ResourceDto {
        public long id;
        public int capacity;
        public String type; // global/local（先只支持 global 也可以）
    }

    public static class ResourceRequirementDto {
        public long id;
        public long jobId;
        public long resourceId;
        public int requirement;
    }
}