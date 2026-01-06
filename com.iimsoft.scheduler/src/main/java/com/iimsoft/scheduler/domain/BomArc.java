package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * BOM 父子关系：parent -> child with quantityPerParent
 */
public class BomArc {
    private Item parent;
    private Item child;
    private int quantityPerParent;

    public BomArc() {
    }

    public BomArc(Item parent, Item child, int quantityPerParent) {
        this.parent = parent;
        this.child = child;
        this.quantityPerParent = quantityPerParent;
    }

    public Item getParent() { return parent; }
    public Item getChild() { return child; }
    public int getQuantityPerParent() { return quantityPerParent; }

    public void setParent(Item parent) { this.parent = parent; }
    public void setChild(Item child) { this.child = child; }
    public void setQuantityPerParent(int quantityPerParent) { this.quantityPerParent = quantityPerParent; }

    @Override
    public String toString() {
        return parent + " -> " + child + " x" + quantityPerParent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BomArc)) return false;
        BomArc bomArc = (BomArc) o;
        return Objects.equals(parent, bomArc.parent) && Objects.equals(child, bomArc.child);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent, child);
    }
}