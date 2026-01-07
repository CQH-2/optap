package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * 生产线工艺产能：定义某条生产线执行某种工艺的最大每小时产量
 */
public class LineProcessCapacity {
    private String id;
    private ProductionLine line;
    private Process process;
    private int maxUnitsPerHour; // 该线执行此工艺的每小时最大产量

    public LineProcessCapacity() {}

    public LineProcessCapacity(String id, ProductionLine line, Process process, int maxUnitsPerHour) {
        this.id = id;
        this.line = line;
        this.process = process;
        this.maxUnitsPerHour = maxUnitsPerHour;
    }

    public String getId() { return id; }
    public ProductionLine getLine() { return line; }
    public Process getProcess() { return process; }
    public int getMaxUnitsPerHour() { return maxUnitsPerHour; }

    public void setId(String id) { this.id = id; }
    public void setLine(ProductionLine line) { this.line = line; }
    public void setProcess(Process process) { this.process = process; }
    public void setMaxUnitsPerHour(int maxUnitsPerHour) { this.maxUnitsPerHour = maxUnitsPerHour; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineProcessCapacity)) return false;
        LineProcessCapacity that = (LineProcessCapacity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "LineProcessCapacity{line=" + (line != null ? line.getCode() : "null") + 
               ", process=" + (process != null ? process.getId() : "null") + 
               ", maxUnitsPerHour=" + maxUnitsPerHour + "}";
    }
}
