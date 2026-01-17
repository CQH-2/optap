package com.iimsoft.schduler.domain;

/**
 * When does an inventory event occur relative to an operation (Allocation)?
 *
 * Typical manufacturing:
 * - Consumption at START (issue materials when starting).
 * - Production at END (receive output when finished).
 */
public enum InventoryEventTime {
    START,
    END
}