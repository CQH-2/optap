package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * 库存快照：包含material、availableQty（可用量）、snapshotTime（时间点）
 */
public class Inventory {
    private String id;
    private Material material;
    private int availableQty;
    private Long snapshotTime; // 时间点（时间戳）

    public Inventory() {}

    public Inventory(String id, Material material, int availableQty, Long snapshotTime) {
        this.id = id;
        this.material = material;
        this.availableQty = availableQty;
        this.snapshotTime = snapshotTime;
    }

    public String getId() { return id; }
    public Material getMaterial() { return material; }
    public int getAvailableQty() { return availableQty; }
    public Long getSnapshotTime() { return snapshotTime; }

    public void setId(String id) { this.id = id; }
    public void setMaterial(Material material) { this.material = material; }
    public void setAvailableQty(int availableQty) { this.availableQty = availableQty; }
    public void setSnapshotTime(Long snapshotTime) { this.snapshotTime = snapshotTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Inventory)) return false;
        Inventory inventory = (Inventory) o;
        return Objects.equals(id, inventory.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Inventory{material=" + (material != null ? material.getId() : "null") + 
               ", availableQty=" + availableQty + ", snapshotTime=" + snapshotTime + "}";
    }
}
