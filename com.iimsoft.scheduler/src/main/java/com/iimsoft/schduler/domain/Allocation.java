package com.iimsoft.schduler.domain;

import java.util.List;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.valuerange.CountableValueRange;
import org.optaplanner.core.api.domain.valuerange.ValueRangeFactory;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.domain.variable.ShadowVariable;
import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;
import com.iimsoft.schduler.domain.ExecutionMode;
import com.iimsoft.schduler.domain.Job;
import com.iimsoft.schduler.domain.JobType;
import com.iimsoft.schduler.domain.Project;
import com.iimsoft.schduler.domain.solver.DelayStrengthComparator;
import com.iimsoft.schduler.domain.solver.ExecutionModeStrengthWeightFactory;
import com.iimsoft.schduler.domain.solver.NotSourceOrSinkAllocationFilter;
import com.iimsoft.schduler.domain.solver.PredecessorsDoneDateUpdatingVariableListener;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;

@PlanningEntity(pinningFilter = NotSourceOrSinkAllocationFilter.class)
@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
public class Allocation extends AbstractPersistable {

    private Job job;

    private Allocation sourceAllocation;
    private Allocation sinkAllocation;
    private List<Allocation> predecessorAllocationList;
    private List<Allocation> successorAllocationList;

    // Planning variables: changes during planning, between score calculations.
    private ExecutionMode executionMode;
    private Integer delay; // In hours

    // Shadow variables
    private Integer predecessorsDoneDate;

    public Allocation() {
    }

    public Allocation(long id, Job job) {
        super(id);
        this.job = job;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public Allocation getSourceAllocation() {
        return sourceAllocation;
    }

    public void setSourceAllocation(Allocation sourceAllocation) {
        this.sourceAllocation = sourceAllocation;
    }

    public Allocation getSinkAllocation() {
        return sinkAllocation;
    }

    public void setSinkAllocation(Allocation sinkAllocation) {
        this.sinkAllocation = sinkAllocation;
    }

    public List<Allocation> getPredecessorAllocationList() {
        return predecessorAllocationList;
    }

    public void setPredecessorAllocationList(List<Allocation> predecessorAllocationList) {
        this.predecessorAllocationList = predecessorAllocationList;
    }

    public List<Allocation> getSuccessorAllocationList() {
        return successorAllocationList;
    }

    public void setSuccessorAllocationList(List<Allocation> successorAllocationList) {
        this.successorAllocationList = successorAllocationList;
    }

    @PlanningVariable(strengthWeightFactoryClass = ExecutionModeStrengthWeightFactory.class)
    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    @PlanningVariable(strengthComparatorClass = DelayStrengthComparator.class)
    public Integer getDelay() {
        return delay;
    }

    public void setDelay(Integer delay) {
        this.delay = delay;
    }

    @ShadowVariable(
            variableListenerClass = PredecessorsDoneDateUpdatingVariableListener.class, sourceVariableName = "executionMode")
    @ShadowVariable(variableListenerClass = PredecessorsDoneDateUpdatingVariableListener.class, sourceVariableName = "delay")
    public Integer getPredecessorsDoneDate() {
        return predecessorsDoneDate;
    }

    public void setPredecessorsDoneDate(Integer predecessorsDoneDate) {
        this.predecessorsDoneDate = predecessorsDoneDate;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    @JsonIgnore
    public Integer getStartDate() {
        if (predecessorsDoneDate == null) {
            return null;
        }
        return predecessorsDoneDate + (delay == null ? 0 : delay);
    }

    @JsonIgnore
    public Integer getEndDate() {
        if (predecessorsDoneDate == null) {
            return null;
        }
        Integer startHour = getStartDate();
        if (startHour == null) {
            return null;
        }
        if (executionMode == null) {
            // During construction phase, execution mode might not be assigned yet
            // Return startHour as a safe default (0 duration)
            return startHour;
        }
        
        int requiredWorkingHours = executionMode.getDuration();
        if (requiredWorkingHours == 0) {
            return startHour;
        }
        
        // Calculate end hour by advancing through working hours only
        return calculateEndHourFromWorkingHours(startHour, requiredWorkingHours);
    }
    
    /**
     * Calculate the end hour by advancing from startHour and accumulating only working hours.
     * Non-working hours (breaks, non-shift hours) are skipped.
     * 
     * @param startHour The starting absolute hour
     * @param requiredWorkingHours The number of effective working hours needed
     * @return The end hour (exclusive, maintaining [start, end) semantics)
     */
    private Integer calculateEndHourFromWorkingHours(int startHour, int requiredWorkingHours) {
        int accumulatedWorkingHours = 0;
        int currentHour = startHour;
        int maxIterations = 365 * 24; // Safety limit: prevent infinite loop
        int iterations = 0;
        
        while (accumulatedWorkingHours < requiredWorkingHours && iterations < maxIterations) {
            if (com.iimsoft.schduler.calendar.WorkCalendar.isWorkingHour(currentHour)) {
                accumulatedWorkingHours++;
            }
            currentHour++;
            iterations++;
        }
        
        if (iterations >= maxIterations) {
            // Safety fallback: if we can't find enough working hours in a year, just return a far future date
            // This should never happen with a properly configured work calendar
            System.err.println("WARNING: Could not find " + requiredWorkingHours + 
                " working hours starting from hour " + startHour + " within " + maxIterations + " hours");
            return startHour + requiredWorkingHours * 2;
        }
        
        return currentHour;
    }

    @JsonIgnore
    public Project getProject() {
        return job.getProject();
    }

    @JsonIgnore
    public int getProjectCriticalPathEndDate() {
        return job.getProject().getCriticalPathEndDate();
    }

    @JsonIgnore
    public JobType getJobType() {
        return job.getJobType();
    }

    public String getLabel() {
        return "Job " + job.getId();
    }

    // ************************************************************************
    // Ranges
    // ************************************************************************

    @ValueRangeProvider
    @JsonIgnore
    public List<ExecutionMode> getExecutionModeRange() {
        return job.getExecutionModeList();
    }

    @ValueRangeProvider
    @JsonIgnore
    public CountableValueRange<Integer> getDelayRange() {
        // Range in hours: 0 to 720 hours (30 days worth)
        return ValueRangeFactory.createIntValueRange(0, 720);
    }

}
