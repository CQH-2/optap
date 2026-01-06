package com.iimsoft.scheduler.domain;

import lombok.Data;

import java.util.Objects;

@Data
public class Item {
    private String code;
    private String name;
    private int leadTime; // 单位：小时

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