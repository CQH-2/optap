package com.iimsoft.scheduler.domain;

import java.util.Objects;

/**
 * 物料
 */
public class Material {
    private String id;
    private String name;

    public Material() {}

    public Material(String id, String name) {
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
        if (!(o instanceof Material)) return false;
        Material material = (Material) o;
        return Objects.equals(id, material.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Material{id='" + id + "', name='" + name + "'}";
    }
}
