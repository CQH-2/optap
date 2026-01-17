package com.iimsoft.schduler.domain;

import java.util.List;

import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import org.optaplanner.examples.projectjobscheduling.domain.Job;
import org.optaplanner.examples.projectjobscheduling.domain.ResourceRequirement;

@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
public class ExecutionMode extends AbstractPersistable {

    private org.optaplanner.examples.projectjobscheduling.domain.Job job;
    private int duration; // In days

    public ExecutionMode() {
    }

    public ExecutionMode(long id, org.optaplanner.examples.projectjobscheduling.domain.Job job) {
        super(id);
        this.job = job;
    }

    private List<ResourceRequirement> resourceRequirementList;

    public org.optaplanner.examples.projectjobscheduling.domain.Job getJob() {
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
