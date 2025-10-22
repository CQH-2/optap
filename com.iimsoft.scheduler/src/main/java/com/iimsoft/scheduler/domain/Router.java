package com.iimsoft.scheduler.domain;

import java.util.HashSet;
import java.util.Set;
import org.optaplanner.core.api.domain.lookup.PlanningId;

public class Router {
    @PlanningId
    private Long id;
    private String name;
    private Set<Item> supportedItems = new HashSet<>();

    public Router() {}
    public Router(Long id, String name) { this.id = id; this.name = name; }

    public boolean supports(Item item) {
        return supportedItems.contains(item);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Set<Item> getSupportedItems() { return supportedItems; }
    public void setSupportedItems(Set<Item> supportedItems) { this.supportedItems = supportedItems; }
}