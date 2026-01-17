package com.iimsoft.schduler.domain;

import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;
import org.optaplanner.examples.projectjobscheduling.domain.ExecutionMode;
import org.optaplanner.examples.projectjobscheduling.domain.resource.Resource;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;

@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
public class ResourceRequirement extends AbstractPersistable {

    private org.optaplanner.examples.projectjobscheduling.domain.ExecutionMode executionMode;
    private Resource resource;
    private int requirement;

    public ResourceRequirement() {
    }

    public ResourceRequirement(long id, org.optaplanner.examples.projectjobscheduling.domain.ExecutionMode executionMode, Resource resource, int requirement) {
        super(id);
        this.executionMode = executionMode;
        this.resource = resource;
        this.requirement = requirement;
    }

    public org.optaplanner.examples.projectjobscheduling.domain.ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public int getRequirement() {
        return requirement;
    }

    public void setRequirement(int requirement) {
        this.requirement = requirement;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    @JsonIgnore
    public boolean isResourceRenewable() {
        return resource.isRenewable();
    }

}
