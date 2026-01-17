package com.iimsoft.schduler.domain;

import java.util.List;

import com.iimsoft.schduler.domain.resource.Resource;
import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.examples.common.domain.AbstractPersistable;






@PlanningSolution
public class Schedule extends AbstractPersistable {

    private List<Project> projectList;
    private List<Job> jobList;
    private List<ExecutionMode> executionModeList;
    private List<Resource> resourceList;
    private List<ResourceRequirement> resourceRequirementList;

    private List<Item> itemList;
    private List<InventoryEvent> inventoryEventList;

    private List<Allocation> allocationList;

    private HardMediumSoftScore score;

    public Schedule() {
    }

    public Schedule(long id) {
        super(id);
    }

    @ProblemFactCollectionProperty
    public List<Project> getProjectList() {
        return projectList;
    }

    public void setProjectList(List<Project> projectList) {
        this.projectList = projectList;
    }

    @ProblemFactCollectionProperty
    public List<Job> getJobList() {
        return jobList;
    }

    public void setJobList(List<Job> jobList) {
        this.jobList = jobList;
    }

    @ProblemFactCollectionProperty
    public List<ExecutionMode> getExecutionModeList() {
        return executionModeList;
    }

    public void setExecutionModeList(List<ExecutionMode> executionModeList) {
        this.executionModeList = executionModeList;
    }

    @ProblemFactCollectionProperty
    public List<Resource> getResourceList() {
        return resourceList;
    }

    public void setResourceList(List<Resource> resourceList) {
        this.resourceList = resourceList;
    }

    @ProblemFactCollectionProperty
    public List<ResourceRequirement> getResourceRequirementList() {
        return resourceRequirementList;
    }

    public void setResourceRequirementList(List<ResourceRequirement> resourceRequirementList) {
        this.resourceRequirementList = resourceRequirementList;
    }

    /**
     * All SKUs that participate in inventory constraints.
     * Shared across projects (global stock).
     */
    @ProblemFactCollectionProperty
    public List<Item> getItemList() {
        return itemList;
    }

    public void setItemList(List<Item> itemList) {
        this.itemList = itemList;
    }

    /**
     * Inventory events (consume/produce) tied to allocations.
     * Shared across projects, therefore enabling cross-project material flow and stock competition.
     */
    @ProblemFactCollectionProperty
    public List<InventoryEvent> getInventoryEventList() {
        return inventoryEventList;
    }

    public void setInventoryEventList(List<InventoryEvent> inventoryEventList) {
        this.inventoryEventList = inventoryEventList;
    }

    @PlanningEntityCollectionProperty
    public List<Allocation> getAllocationList() {
        return allocationList;
    }

    public void setAllocationList(List<Allocation> allocationList) {
        this.allocationList = allocationList;
    }

    @PlanningScore
    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

}
