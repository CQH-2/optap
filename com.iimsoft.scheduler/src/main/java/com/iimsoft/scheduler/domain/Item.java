package com.iimsoft.scheduler.domain;

import lombok.Data;

import java.util.Objects;

@Data
public class Item {
    private String code;
    private String name;
    private int leadTime; // 单位：天（对于自制件是生产周期，对于采购件是采购前置期）
    private ItemType itemType; // 物料类型：自制/采购/通用

    public Item() {
        this.itemType = ItemType.GENERIC; // 默认通用
    }

    public Item(String code, String name) {
        this(code, name, 0);
    }

    public Item(String code, String name, int leadTime) {
        this(code, name, leadTime, ItemType.GENERIC);
    }
    
    public Item(String code, String name, int leadTime, ItemType itemType) {
        this.code = code;
        this.name = name;
        this.leadTime = leadTime;
        this.itemType = itemType != null ? itemType : ItemType.GENERIC;
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