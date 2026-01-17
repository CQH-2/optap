package com.iimsoft.schduler.domain;

import java.util.List;

import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;
import org.optaplanner.examples.projectjobscheduling.domain.Job;
import org.optaplanner.examples.projectjobscheduling.domain.resource.LocalResource;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;

@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
public class Project extends AbstractPersistable {

    private int releaseDate;
    private int criticalPathDuration;

    private List<LocalResource> localResourceList;
    private List<org.optaplanner.examples.projectjobscheduling.domain.Job> jobList;

    public Project() {
    }

    public Project(long id, int releaseDate, int criticalPathDuration) {
        super(id);
        this.releaseDate = releaseDate;
        this.criticalPathDuration = criticalPathDuration;
    }

    public int getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(int releaseDate) {
        this.releaseDate = releaseDate;
    }

    public int getCriticalPathDuration() {
        return criticalPathDuration;
    }

    public void setCriticalPathDuration(int criticalPathDuration) {
        this.criticalPathDuration = criticalPathDuration;
    }

    public List<LocalResource> getLocalResourceList() {
        return localResourceList;
    }

    public void setLocalResourceList(List<LocalResource> localResourceList) {
        this.localResourceList = localResourceList;
    }

    public List<org.optaplanner.examples.projectjobscheduling.domain.Job> getJobList() {
        return jobList;
    }

    public void setJobList(List<Job> jobList) {
        this.jobList = jobList;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    @JsonIgnore
    public int getCriticalPathEndDate() {
        return releaseDate + criticalPathDuration;
    }

    public String getLabel() {
        return "Project " + id;
    }

}
