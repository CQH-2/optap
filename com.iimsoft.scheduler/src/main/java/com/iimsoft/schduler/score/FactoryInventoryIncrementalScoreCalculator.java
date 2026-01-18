package com.iimsoft.schduler.score;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

import com.iimsoft.schduler.calendar.WorkCalendar;
import com.iimsoft.schduler.domain.Allocation;
import com.iimsoft.schduler.domain.ExecutionMode;
import com.iimsoft.schduler.domain.InventoryEvent;
import com.iimsoft.schduler.domain.InventoryEventTime;
import com.iimsoft.schduler.domain.Item;
import com.iimsoft.schduler.domain.JobType;
import com.iimsoft.schduler.domain.Project;
import com.iimsoft.schduler.domain.ResourceRequirement;
import com.iimsoft.schduler.domain.Schedule;
import com.iimsoft.schduler.domain.resource.Resource;

/**
 * Incremental score calculator that supports:
 *
 * Hard:
 *  - Renewable resource capacity per time slot (hour) [only working hours consume capacity].
 *  - Nonrenewable resource capacity (total consumption <= capacity).
 *  - Route-C inventory: inventory balance of each Item must never drop below 0 over time.
 *
 * Medium:
 *  - Total project delay: max(0, sinkEndDate - projectCriticalPathEndDate).
 *
 * Soft:
 *  - Makespan: max sink end date.
 *
 * 时间单位说明（按最新需求）：
 * - startDate/endDate/delay/duration 全部按“小时”理解
 * - ExecutionMode.duration = 有效工时（只在 WorkCalendar.isWorkingHour==true 的小时计时）
 *
 * 资源消耗策略（允许跨休息/跨夜班）：
 * - Allocation 可能跨非工作小时，但这些小时不消耗 renewable 资源
 * - 因此 renewable 资源占用只统计工作小时，不会因为跨休息导致 hard 违规
 */
public class FactoryInventoryIncrementalScoreCalculator
    implements IncrementalScoreCalculator<Schedule, HardMediumSoftScore> {

    // -----------------------------
    // Resource capacity tracking
    // -----------------------------

    /** For renewable resources: resource -> (hour -> usedCapacity) */
    private final Map renewableUsedMap = new HashMap();

    /** For nonrenewable resources: resource -> usedTotal */
    private final Map nonrenewableUsedMap = new HashMap();

    // -----------------------------
    // Inventory tracking (Route C)
    // -----------------------------

    private Map inventoryTrackerMap;
    private Map allocationToEventListMap;
    private Map eventLastDayMap;

    // -----------------------------
    // Project delay + makespan tracking
    // -----------------------------

    private Map projectSinkEndDateMap;
    private int maximumSinkEndDate;

    // -----------------------------
    // Score totals
    // -----------------------------

    private int hardScore;
    private int mediumScore;
    private int softScore;

    private Schedule workingSolution;

    @Override
    public void resetWorkingSolution(Schedule schedule) {
        this.workingSolution = schedule;

        renewableUsedMap.clear();
        nonrenewableUsedMap.clear();

        inventoryTrackerMap = new HashMap();
        if (schedule.getItemList() != null) {
            for (Object o : schedule.getItemList()) {
                Item item = (Item) o;
                inventoryTrackerMap.put(item, new InventoryTracker(item));
            }
        }

        allocationToEventListMap = new HashMap();
        eventLastDayMap = new HashMap();
        if (schedule.getInventoryEventList() != null) {
            for (Object o : schedule.getInventoryEventList()) {
                InventoryEvent event = (InventoryEvent) o;
                if (event.getAllocation() == null) {
                    continue;
                }
                List list = (List) allocationToEventListMap.computeIfAbsent(event.getAllocation(), k -> new ArrayList());
                list.add(event);
                eventLastDayMap.put(event, null);
            }
        }

        projectSinkEndDateMap = new HashMap();
        maximumSinkEndDate = 0;

        hardScore = 0;
        mediumScore = 0;
        softScore = 0;

        if (schedule.getAllocationList() != null) {
            for (Object o : schedule.getAllocationList()) {
                insert((Allocation) o, schedule);
            }
        }
    }

    @Override
    public void beforeEntityAdded(Object entity) { }

    @Override
    public void afterEntityAdded(Object entity) { }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        retract((Allocation) entity, workingSolution);
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        insert((Allocation) entity, workingSolution);
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
        retract((Allocation) entity, workingSolution);
    }

    @Override
    public void afterEntityRemoved(Object entity) { }

    private void insert(Allocation allocation, Schedule schedule) {
        insertResourceCapacities(allocation);
        insertInventory(allocation);
        insertProjectMetrics(allocation);
    }

    private void retract(Allocation allocation, Schedule schedule) {
        retractProjectMetrics(allocation);
        retractInventory(allocation);
        retractResourceCapacities(allocation);
    }

    // ----------------------------------------------------------------
    // A) Resource capacities (hour-based, shift-aware)
    // ----------------------------------------------------------------

    private void insertResourceCapacities(Allocation allocation) {
        ExecutionMode mode = allocation.getExecutionMode();
        if (mode == null || allocation.getJobType() != JobType.STANDARD) {
            return;
        }
        Integer start = allocation.getStartDate();
        Integer end = allocation.getEndDate();
        if (start == null || end == null) {
            return;
        }

        if (mode.getResourceRequirementList() == null) {
            return;
        }

        for (Object obj : mode.getResourceRequirementList()) {
            ResourceRequirement rr = (ResourceRequirement) obj;
            Resource resource = rr.getResource();
            int req = rr.getRequirement();

            if (resource.isRenewable()) {
                Map usedPerHour = (Map) renewableUsedMap.computeIfAbsent(resource, r -> new HashMap());
                for (int hour = start; hour < end; hour++) {
                    // 关键：只在工作小时消耗资源（白班+夜班由 WorkCalendarConfig 决定）
                    if (!WorkCalendar.isWorkingHour(hour)) {
                        continue;
                    }
                    int oldUsed = (int) usedPerHour.getOrDefault(hour, 0);
                    int newUsed = oldUsed + req;

                    hardScore += renewableOveruseDelta(resource, oldUsed, newUsed);

                    usedPerHour.put(hour, newUsed);
                }
            } else {
                int oldUsed = (int) nonrenewableUsedMap.getOrDefault(resource, 0);
                int newUsed = oldUsed + req;

                hardScore += nonrenewableOveruseDelta(resource, oldUsed, newUsed);

                nonrenewableUsedMap.put(resource, newUsed);
            }
        }
    }

    private void retractResourceCapacities(Allocation allocation) {
        ExecutionMode mode = allocation.getExecutionMode();
        if (mode == null || allocation.getJobType() != JobType.STANDARD) {
            return;
        }
        Integer start = allocation.getStartDate();
        Integer end = allocation.getEndDate();
        if (start == null || end == null) {
            return;
        }

        if (mode.getResourceRequirementList() == null) {
            return;
        }

        for (Object obj : mode.getResourceRequirementList()) {
            ResourceRequirement rr = (ResourceRequirement) obj;
            Resource resource = rr.getResource();
            int req = rr.getRequirement();

            if (resource.isRenewable()) {
                Map usedPerHour = (Map) renewableUsedMap.get(resource);
                if (usedPerHour == null) {
                    continue;
                }
                for (int hour = start; hour < end; hour++) {
                    if (!WorkCalendar.isWorkingHour(hour)) {
                        continue;
                    }
                    int oldUsed = (int) usedPerHour.getOrDefault(hour, 0);
                    int newUsed = oldUsed - req;

                    hardScore += renewableOveruseDelta(resource, oldUsed, newUsed);

                    if (newUsed == 0) {
                        usedPerHour.remove(hour);
                    } else {
                        usedPerHour.put(hour, newUsed);
                    }
                }
            } else {
                int oldUsed = (int) nonrenewableUsedMap.getOrDefault(resource, 0);
                int newUsed = oldUsed - req;

                hardScore += nonrenewableOveruseDelta(resource, oldUsed, newUsed);

                if (newUsed == 0) {
                    nonrenewableUsedMap.remove(resource);
                } else {
                    nonrenewableUsedMap.put(resource, newUsed);
                }
            }
        }
    }

    private int renewableOveruseDelta(Resource resource, int oldUsed, int newUsed) {
        int cap = resource.getCapacity();
        int oldOver = Math.max(0, oldUsed - cap);
        int newOver = Math.max(0, newUsed - cap);
        return -(newOver - oldOver);
    }

    private int nonrenewableOveruseDelta(Resource resource, int oldUsed, int newUsed) {
        int cap = resource.getCapacity();
        int oldOver = Math.max(0, oldUsed - cap);
        int newOver = Math.max(0, newUsed - cap);
        return -(newOver - oldOver);
    }

    // ----------------------------------------------------------------
    // B) Inventory (eventDate is hour-based via Allocation)
    // ----------------------------------------------------------------

    private void insertInventory(Allocation allocation) {
        List events = (List) allocationToEventListMap.get(allocation);
        if (events == null) {
            return;
        }
        for (Object o : events) {
            InventoryEvent event = (InventoryEvent) o;

            Integer t = computeEventTime(event); // hour
            eventLastDayMap.put(event, t);

            if (t == null) {
                continue;
            }
            InventoryTracker tracker = getOrCreateTracker(event.getItem());

            hardScore -= tracker.getHardScoreContribution();
            tracker.addDelta(t, event.getQuantity());
            hardScore += tracker.getHardScoreContribution();
        }
    }

    private void retractInventory(Allocation allocation) {
        List events = (List) allocationToEventListMap.get(allocation);
        if (events == null) {
            return;
        }
        for (Object o : events) {
            InventoryEvent event = (InventoryEvent) o;

            Integer lastT = (Integer) eventLastDayMap.get(event);
            eventLastDayMap.put(event, null);

            if (lastT == null) {
                continue;
            }
            InventoryTracker tracker = getOrCreateTracker(event.getItem());

            hardScore -= tracker.getHardScoreContribution();
            tracker.removeDelta(lastT, event.getQuantity());
            hardScore += tracker.getHardScoreContribution();
        }
    }

    private InventoryTracker getOrCreateTracker(Item item) {
        InventoryTracker tracker = (InventoryTracker) inventoryTrackerMap.get(item);
        if (tracker == null) {
            tracker = new InventoryTracker(item);
            inventoryTrackerMap.put(item, tracker);
        }
        return tracker;
    }

    private Integer computeEventTime(InventoryEvent event) {
        if (event.getAllocation() == null) {
            return null;
        }
        return event.getTimePolicy() == InventoryEventTime.START
                ? event.getAllocation().getStartDate()
                : event.getAllocation().getEndDate();
    }

    // ----------------------------------------------------------------
    // C) Delay + makespan (hour-based)
    // ----------------------------------------------------------------

    private void insertProjectMetrics(Allocation allocation) {
        if (allocation.getJobType() != JobType.SINK) {
            return;
        }
        Integer endDate = allocation.getEndDate();
        if (endDate == null) {
            return;
        }
        Project project = allocation.getProject();
        projectSinkEndDateMap.put(project, endDate);

        int delay = Math.max(0, endDate - allocation.getProjectCriticalPathEndDate());
        mediumScore -= delay;

        if (endDate > maximumSinkEndDate) {
            softScore -= (endDate - maximumSinkEndDate);
            maximumSinkEndDate = endDate;
        }
    }

    private void retractProjectMetrics(Allocation allocation) {
        if (allocation.getJobType() != JobType.SINK) {
            return;
        }
        Integer endDate = allocation.getEndDate();
        if (endDate == null) {
            return;
        }
        Project project = allocation.getProject();
        projectSinkEndDateMap.remove(project);

        int delay = Math.max(0, endDate - allocation.getProjectCriticalPathEndDate());
        mediumScore += delay;

        if (endDate == maximumSinkEndDate) {
            int newMax = 0;
            for (Object v : projectSinkEndDateMap.values()) {
                int vv = (Integer) v;
                if (vv > newMax) {
                    newMax = vv;
                }
            }
            softScore += (maximumSinkEndDate - newMax);
            maximumSinkEndDate = newMax;
        }
    }

    @Override
    public HardMediumSoftScore calculateScore() {
        return HardMediumSoftScore.of(hardScore, mediumScore, softScore);
    }
}
