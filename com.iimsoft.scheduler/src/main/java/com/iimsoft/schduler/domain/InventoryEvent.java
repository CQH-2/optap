package com.iimsoft.schduler.domain;

import org.optaplanner.examples.common.domain.AbstractPersistable;
import org.optaplanner.examples.common.persistence.jackson.JacksonUniqueIdGenerator;
import com.iimsoft.schduler.domain.Allocation;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inventory delta (consume/produce) tied to a scheduled Allocation.
 *
 * quantity semantics:
 * - quantity < 0  => consumption (stock decreases)
 * - quantity > 0  => production  (stock increases)
 *
 * eventDate is derived from Allocation:
 * - START => allocation.getStartDate()
 * - END   => allocation.getEndDate()
 *
 * Multi-project coupling:
 * - If Project A produces Item X (+) and Project B consumes Item X (-),
 *   they compete and depend through the SAME Item instance.
 */
@JsonIdentityInfo(generator = JacksonUniqueIdGenerator.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class InventoryEvent extends AbstractPersistable {

    private Allocation allocation;
    private Item item;

    /** Negative = consume, Positive = produce. */
    private int quantity;

    private InventoryEventTime timePolicy;

    public InventoryEvent() {
    }

    public InventoryEvent(long id, Allocation allocation, Item item, int quantity, InventoryEventTime timePolicy) {
        super(id);
        this.allocation = allocation;
        this.item = item;
        this.quantity = quantity;
        this.timePolicy = timePolicy;
    }

    public Allocation getAllocation() {
        return allocation;
    }

    public void setAllocation(Allocation allocation) {
        this.allocation = allocation;
    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public InventoryEventTime getTimePolicy() {
        return timePolicy;
    }

    public void setTimePolicy(InventoryEventTime timePolicy) {
        this.timePolicy = timePolicy;
    }

    // ----------------------------------------------------------------
    // Derived helpers (do NOT use for retract timing; score calc caches)
    // ----------------------------------------------------------------

    /**
     * Derived day on the timeline. Returns null when allocation is not scheduled yet.
     *
     * Note: In incremental scoring, do not rely on this during retract; use cached lastDay instead.
     */
    @JsonIgnore
    public Integer getEventDate() {
        if (allocation == null) {
            return null;
        }
        return timePolicy == InventoryEventTime.START ? allocation.getStartDate() : allocation.getEndDate();
    }

    @Override
    public String toString() {
        return "InvEvent(item=" + item + ", qty=" + quantity + ", at=" + timePolicy + ", alloc=" + allocation + ")";
    }
}