package com.iimsoft.schduler.score;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

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
 *  - Renewable resource capacity per day (same idea as ConstraintProvider version).
 *  - Nonrenewable resource capacity (total consumption <= capacity).
 *  - Route-C inventory: inventory balance of each Item must never drop below 0 over time.
 *
 * Medium:
 *  - Total project delay: max(0, sinkEndDate - projectCriticalPathEndDate).
 *
 * Soft:
 *  - Makespan: max sink end date.
 *
 * Why incremental?
 *  - Inventory "prefix sum over time" is much easier and faster to maintain via trackers (InventoryTracker).
 *
 * IMPORTANT:
 *  - We cache each InventoryEvent's last effective day (eventLastDayMap) to avoid retracting from a wrong day
 *    when the allocation schedule changes.
 */
public class FactoryInventoryIncrementalScoreCalculator implements IncrementalScoreCalculator<Schedule, HardMediumSoftScore> {

    // -----------------------------
    // Resource capacity tracking
    // -----------------------------

    /** For renewable resources: resource -> (hour -> usedCapacity) */
    private final Map<Resource, Map<Integer, Integer>> renewableUsedMap = new HashMap<>();

    /** For nonrenewable resources: resource -> usedTotal */
    private final Map<Resource, Integer> nonrenewableUsedMap = new HashMap<>();

    // -----------------------------
    // Inventory tracking (Route C)
    // -----------------------------

    private Map<Item, InventoryTracker> inventoryTrackerMap;
    private Map<Allocation, List<InventoryEvent>> allocationToEventListMap;
    private Map<InventoryEvent, Integer> eventLastHourMap;

    // -----------------------------
    // Project delay + makespan tracking
    // -----------------------------

    private Map<Project, Integer> projectSinkEndDateMap;
    private int maximumSinkEndDate;

    // -----------------------------
    // Score totals
    // -----------------------------

    private int hardScore;
    private int mediumScore;
    private int softScore;

    // OptaPlanner calls resetWorkingSolution once and then variable change hooks many times.
    private Schedule workingSolution;

    @Override
    public void resetWorkingSolution(Schedule schedule) {
        this.workingSolution = schedule;
        
        renewableUsedMap.clear();
        nonrenewableUsedMap.clear();

        // Inventory trackers (global, cross-project)
        inventoryTrackerMap = new HashMap<>();
        if (schedule.getItemList() != null) {
            for (Item item : schedule.getItemList()) {
                inventoryTrackerMap.put(item, new InventoryTracker(item));
            }
        }

        allocationToEventListMap = new HashMap<>();
        eventLastHourMap = new HashMap<>();
        if (schedule.getInventoryEventList() != null) {
            for (InventoryEvent event : schedule.getInventoryEventList()) {
                if (event.getAllocation() == null) {
                    continue;
                }
                allocationToEventListMap.computeIfAbsent(event.getAllocation(), k -> new ArrayList<>()).add(event);
                eventLastHourMap.put(event, null); // unknown until first insert
            }
        }

        projectSinkEndDateMap = new HashMap<>();
        maximumSinkEndDate = 0;

        hardScore = 0;
        mediumScore = 0;
        softScore = 0;

        // Insert all allocations to initialize trackers and scores
        if (schedule.getAllocationList() != null) {
            for (Allocation allocation : schedule.getAllocationList()) {
                insert(allocation, schedule);
            }
        }
    }

    @Override
    public void beforeEntityAdded(Object entity) {
        // no-op
    }

    @Override
    public void afterEntityAdded(Object entity) {
        // Not used in typical solving of this example; still keep consistent.
        // We cannot access Schedule here; OptaPlanner doesn't pass it.
        // In this example the entity set is fixed, so ignore.
    }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        // We need access to workingSolution for resourceRequirementList, but interface doesn't provide it here.
        // Therefore we store it in a field when resetWorkingSolution is called.
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
    public void afterEntityRemoved(Object entity) {
        // no-op
    }

    private void insert(Allocation allocation, Schedule schedule) {
        // A) Resource capacities (hard)
        insertResourceCapacities(allocation, schedule);

        // B) Inventory (hard)
        insertInventory(allocation);

        // C) Delay + makespan (medium/soft)
        insertProjectMetrics(allocation);
    }

    private void retract(Allocation allocation, Schedule schedule) {
        // C) Delay + makespan first or last doesn't matter as long as symmetric; keep same order as insert but retract first.
        retractProjectMetrics(allocation);

        // B) Inventory
        retractInventory(allocation);

        // A) Resources
        retractResourceCapacities(allocation, schedule);
    }

    // ----------------------------------------------------------------
    // A) Resource capacities
    // ----------------------------------------------------------------

    private void insertResourceCapacities(Allocation allocation, Schedule schedule) {
        ExecutionMode mode = allocation.getExecutionMode();
        if (mode == null || allocation.getJobType() != JobType.STANDARD) {
            return;
        }
        Integer start = allocation.getStartDate();
        Integer end = allocation.getEndDate();
        if (start == null || end == null) {
            return;
        }

        // For each resource requirement of the chosen mode, update renewable per-day usage or nonrenewable total usage.
        if (mode.getResourceRequirementList() == null) {
            return;
        }
        for (ResourceRequirement rr : mode.getResourceRequirementList()) {
            Resource resource = rr.getResource();
            int req = rr.getRequirement();

            if (resource.isRenewable()) {
                Map<Integer, Integer> usedPerHour = renewableUsedMap.computeIfAbsent(resource, r -> new HashMap<>());
                // Only count usage during working hours
                for (int hour = start; hour < end; hour++) {
                    if (!com.iimsoft.schduler.calendar.WorkCalendar.isWorkingHour(hour)) {
                        continue; // Skip non-working hours
                    }
                    int oldUsed = usedPerHour.getOrDefault(hour, 0);
                    int newUsed = oldUsed + req;

                    hardScore += renewableOveruseDelta(resource, oldUsed, newUsed);

                    usedPerHour.put(hour, newUsed);
                }
            } else {
                int oldUsed = nonrenewableUsedMap.getOrDefault(resource, 0);
                int newUsed = oldUsed + req;

                hardScore += nonrenewableOveruseDelta(resource, oldUsed, newUsed);

                nonrenewableUsedMap.put(resource, newUsed);
            }
        }
    }

    private void retractResourceCapacities(Allocation allocation, Schedule schedule) {
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
        for (ResourceRequirement rr : mode.getResourceRequirementList()) {
            Resource resource = rr.getResource();
            int req = rr.getRequirement();

            if (resource.isRenewable()) {
                Map<Integer, Integer> usedPerHour = renewableUsedMap.get(resource);
                if (usedPerHour == null) {
                    continue;
                }
                // Only retract usage from working hours
                for (int hour = start; hour < end; hour++) {
                    if (!com.iimsoft.schduler.calendar.WorkCalendar.isWorkingHour(hour)) {
                        continue; // Skip non-working hours
                    }
                    int oldUsed = usedPerHour.getOrDefault(hour, 0);
                    int newUsed = oldUsed - req;

                    hardScore += renewableOveruseDelta(resource, oldUsed, newUsed);

                    if (newUsed == 0) {
                        usedPerHour.remove(hour);
                    } else {
                        usedPerHour.put(hour, newUsed);
                    }
                }
            } else {
                int oldUsed = nonrenewableUsedMap.getOrDefault(resource, 0);
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

    /**
     * Computes change in hardScore due to changing used from oldUsed to newUsed for a renewable resource at ONE hour.
     * Score convention: hardScore is <= 0; overuse of X => -X.
     */
    private int renewableOveruseDelta(Resource resource, int oldUsed, int newUsed) {
        int cap = resource.getCapacity();
        int oldOver = Math.max(0, oldUsed - cap);
        int newOver = Math.max(0, newUsed - cap);
        // Overuse increases => hardScore becomes more negative
        return -(newOver - oldOver);
    }

    /**
     * Computes change in hardScore due to changing usedTotal for a nonrenewable resource.
     */
    private int nonrenewableOveruseDelta(Resource resource, int oldUsed, int newUsed) {
        int cap = resource.getCapacity();
        int oldOver = Math.max(0, oldUsed - cap);
        int newOver = Math.max(0, newUsed - cap);
        return -(newOver - oldOver);
    }

    // ----------------------------------------------------------------
    // B) Inventory
    // ----------------------------------------------------------------

    private void insertInventory(Allocation allocation) {
        List<InventoryEvent> events = allocationToEventListMap.get(allocation);
        if (events == null) {
            return;
        }
        for (InventoryEvent event : events) {
            Integer hour = computeEventHour(event);
            eventLastHourMap.put(event, hour);

            if (hour == null) {
                continue;
            }
            InventoryTracker tracker = getOrCreateTracker(event.getItem());

            // Adjust score by removing old contribution, applying delta, then adding new contribution
            hardScore -= tracker.getHardScoreContribution();
            tracker.addDelta(hour, event.getQuantity());
            hardScore += tracker.getHardScoreContribution();
        }
    }

    private void retractInventory(Allocation allocation) {
        List<InventoryEvent> events = allocationToEventListMap.get(allocation);
        if (events == null) {
            return;
        }
        for (InventoryEvent event : events) {
            Integer lastHour = eventLastHourMap.get(event); // cached, safe
            eventLastHourMap.put(event, null);

            if (lastHour == null) {
                continue;
            }
            InventoryTracker tracker = getOrCreateTracker(event.getItem());

            hardScore -= tracker.getHardScoreContribution();
            tracker.removeDelta(lastHour, event.getQuantity());
            hardScore += tracker.getHardScoreContribution();
        }
    }

    private InventoryTracker getOrCreateTracker(Item item) {
        InventoryTracker tracker = inventoryTrackerMap.get(item);
        if (tracker == null) {
            tracker = new InventoryTracker(item);
            inventoryTrackerMap.put(item, tracker);
        }
        return tracker;
    }

    private Integer computeEventHour(InventoryEvent event) {
        if (event.getAllocation() == null) {
            return null;
        }
        if (event.getTimePolicy() == InventoryEventTime.START) {
            return event.getAllocation().getStartDate();
        } else {
            return event.getAllocation().getEndDate();
        }
    }

    // ----------------------------------------------------------------
    // C) Delay + makespan
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

        // medium: delay = max(0, end - criticalPathEnd)
        int delay = Math.max(0, endDate - allocation.getProjectCriticalPathEndDate());
        mediumScore -= delay;

        // soft: makespan = max sink end
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
            // recompute max
            int newMax = 0;
            for (Integer v : projectSinkEndDateMap.values()) {
                if (v > newMax) {
                    newMax = v;
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
