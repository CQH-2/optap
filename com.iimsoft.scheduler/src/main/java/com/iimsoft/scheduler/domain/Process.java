package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * 工艺（或工序类型）：描述一项加工类型，如Assembly、Testing、Packing
 */
public class Process {
    private String id;
    private String name;

    public Process() {}

    public Process(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Process)) return false;
        Process process = (Process) o;
        return Objects.equals(id, process.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Process{id='" + id + "', name='" + name + "'}";
    }
}
