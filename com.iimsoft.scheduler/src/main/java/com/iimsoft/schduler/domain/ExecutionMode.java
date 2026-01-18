package com.iimsoft.schduler.domain;

import java.util.List;

import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.iimsoft.schduler.domain.Job;
import com.iimsoft.schduler.domain.ResourceRequirement;

@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
public class ExecutionMode extends AbstractPersistable {

    private Job job;
    /**
     * 工期（按“有效工时”计）。
     * <p>
     * 注意：这里的 duration 单位是“小时”，并且只统计工作小时（由 WorkCalendar 决定哪些小时可工作）。
     */
    private int duration;

    public ExecutionMode() {
    }

    public ExecutionMode(long id, Job job) {
        super(id);
        this.job = job;
    }

    private List<ResourceRequirement> resourceRequirementList;

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public List<ResourceRequirement> getResourceRequirementList() {
        return resourceRequirementList;
    }

    public void setResourceRequirementList(List<ResourceRequirement> resourceRequirementList) {
        this.resourceRequirementList = resourceRequirementList;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

}
