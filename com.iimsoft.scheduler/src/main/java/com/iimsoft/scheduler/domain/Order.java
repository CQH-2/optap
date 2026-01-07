package com.iimsoft.scheduler.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 订单：包含多个工序，有交货期和优先级
 */
public class Order {
    private String id;
    private LocalDate dueDate;
    private int priority; // 优先级，1-10，10为最高
    private List<Operation> operations; // 订单包含的工序列表

    public Order() {
        this.operations = new ArrayList<>();
        this.priority = 5; // 默认优先级
    }

    public Order(String id, LocalDate dueDate, int priority) {
        this.id = id;
        this.dueDate = dueDate;
        this.priority = priority;
        this.operations = new ArrayList<>();
    }

    public String getId() { return id; }
    public LocalDate getDueDate() { return dueDate; }
    public int getPriority() { return priority; }
    public List<Operation> getOperations() { return operations; }

    public void setId(String id) { this.id = id; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setPriority(int priority) { this.priority = priority; }
    public void setOperations(List<Operation> operations) { this.operations = operations; }

    public void addOperation(Operation operation) {
        if (operation != null) {
            this.operations.add(operation);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order)) return false;
        Order order = (Order) o;
        return Objects.equals(id, order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Order{id='" + id + "', dueDate=" + dueDate + ", priority=" + priority + "}";
    }
}
