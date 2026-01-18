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
import com.iimsoft.schduler.domain.solver.DelayStrengthComparator;
import com.iimsoft.schduler.domain.solver.ExecutionModeStrengthWeightFactory;
import com.iimsoft.schduler.domain.solver.NotSourceOrSinkAllocationFilter;
import com.iimsoft.schduler.domain.solver.PredecessorsDoneDateUpdatingVariableListener;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.iimsoft.schduler.calendar.WorkCalendar;

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
    /**
     * 注意：原示例注释是 In days。
     * 按你最新需求（小时 + 班次），这里应当按“小时”理解 delay。
     */
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
        Integer start = getStartDate();
        if (start == null) {
            return null;
        }
        int requiredWorkingHours = (executionMode == null ? 0 : executionMode.getDuration());
        if (requiredWorkingHours <= 0) {
            return start;
        }

        int t = start;
        int done = 0;

        // 防止配置错误导致死循环：最多推 365 天
        int safety = 24 * 365;

        while (done < requiredWorkingHours && safety-- > 0) {
            if (WorkCalendar.isWorkingHour(t)) {
                done++;
            }
            t++;
        }
        return t;
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
        // 小时粒度建议把 range 加大一点，否则容易卡住
        return ValueRangeFactory.createIntValueRange(0, 24 * 30); // 0..720 小时
    }

}
