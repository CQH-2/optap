package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * 工艺：只能生产一个物料，速度：件/小时
 */
public class Router {
    private String code;
    private Item item;
    private int speedPerHour;

    public Router() {}

    public Router(String code, Item item, int speedPerHour) {
        this.code = code;
        this.item = item;
        this.speedPerHour = speedPerHour;
    }

    public String getCode() { return code; }
    public Item getItem() { return item; }
    public int getSpeedPerHour() { return speedPerHour; }

    public void setCode(String code) { this.code = code; }
    public void setItem(Item item) { this.item = item; }
    public void setSpeedPerHour(int speedPerHour) { this.speedPerHour = speedPerHour; }

    @Override
    public String toString() {
        return code + "(" + item + "@" + speedPerHour + "/h)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Router)) return false;
        Router router = (Router) o;
        return Objects.equals(code, router.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}