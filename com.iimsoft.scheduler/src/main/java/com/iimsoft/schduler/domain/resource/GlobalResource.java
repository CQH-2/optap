package com.iimsoft.schduler.domain.resource;

import org.optaplanner.examples.projectjobscheduling.domain.resource.Resource;

public class GlobalResource extends Resource {

    public GlobalResource() {
    }

    public GlobalResource(long id, int capacity) {
        super(id, capacity);
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    @Override
    public boolean isRenewable() {
        return true;
    }

}
