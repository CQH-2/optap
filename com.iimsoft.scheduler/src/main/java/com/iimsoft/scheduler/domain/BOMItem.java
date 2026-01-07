package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * 物料清单项：关联一个Operation，指明需要哪种Material和需要多少quantity
 */
public class BOMItem {
    private String id;
    private Operation operation;
    private Material material;
    private int quantity;

    public BOMItem() {}

    public BOMItem(String id, Operation operation, Material material, int quantity) {
        this.id = id;
        this.operation = operation;
        this.material = material;
        this.quantity = quantity;
    }

    public String getId() { return id; }
    public Operation getOperation() { return operation; }
    public Material getMaterial() { return material; }
    public int getQuantity() { return quantity; }

    public void setId(String id) { this.id = id; }
    public void setOperation(Operation operation) { this.operation = operation; }
    public void setMaterial(Material material) { this.material = material; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BOMItem)) return false;
        BOMItem bomItem = (BOMItem) o;
        return Objects.equals(id, bomItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "BOMItem{operation=" + (operation != null ? operation.getId() : "null") + 
               ", material=" + (material != null ? material.getId() : "null") + 
               ", quantity=" + quantity + "}";
    }
}
