package com.iimsoft.schduler.domain.solver;

import org.optaplanner.core.api.domain.entity.PinningFilter;
import com.iimsoft.schduler.domain.Allocation;
import com.iimsoft.schduler.domain.JobType;
import com.iimsoft.schduler.domain.Schedule;

public class NotSourceOrSinkAllocationFilter implements PinningFilter<Schedule, Allocation> {

    @Override
    public boolean accept(Schedule schedule, Allocation allocation) {
        JobType jobType = allocation.getJob().getJobType();
        return jobType == JobType.SOURCE || jobType == JobType.SINK;
    }

}
