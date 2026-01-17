package com.iimsoft.schduler.domain;

import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A stock keeping unit (SKU), shared across all projects.
 *
 * IMPORTANT for multi-project inventory coupling:
 * - The same item code must map to the SAME Item instance in the Schedule.
 *   (Otherwise each project would get its own "virtual stock" and they won't compete.)
 *
 * Inventory constraint (Route C) will ensure:
 *   initialStock + sum(events.quantity with eventDate <= t) >= 0  for all t
 */
@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Item extends AbstractPersistable {

    /** SKU code/name for debugging and external mapping. */
    private String code;

    /** Initial on-hand inventory at time 0 (discrete time unit). */
    private int initialStock;

    public Item() {
    }

    public Item(long id, String code, int initialStock) {
        super(id);
        this.code = code;
        this.initialStock = initialStock;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getInitialStock() {
        return initialStock;
    }

    public void setInitialStock(int initialStock) {
        this.initialStock = initialStock;
    }

    @Override
    public String toString() {
        return code == null ? super.toString() : code;
    }
}