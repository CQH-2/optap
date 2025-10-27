package com.iimsoft.scheduler.domain;

import java.util.Objects;

public class Item {
    private String code;
    private String name;
    private int leadTime; // 单位：天

    public Item() {
    }

    public Item(String code, String name) {
        this(code, name, 0);
    }

    public Item(String code, String name, int leadTime) {
        this.code = code;
        this.name = name;
        this.leadTime = leadTime;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public int getLeadTime() { return leadTime; }

    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setLeadTime(int leadTime) { this.leadTime = leadTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Item)) return false;
        Item item = (Item) o;
        return Objects.equals(code, item.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return code;
    }
}