package com.iimsoft.schduler.score;

import com.iimsoft.schduler.domain.Item;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


/**
 * Tracks inventory balance for one Item over the discrete timeline.
 *
 * We store per-day deltas and compute minimum prefix balance:
 *   balance(t) = initialStock + sum_{day<=t}(delta(day))
 *   minBalance = min_t(balance(t))
 *
 * Hard penalty contribution:
 * - if minBalance >= 0 => 0
 * - else => minBalance (negative; e.g. -3 means shortage of 3 at some day)
 *
 * Note: This is per-item tracker. The score calculator maintains one per Item and
 * updates it incrementally when events are inserted/retracted/moved across days.
 */
public class InventoryTracker {

    private final Item item;
    private final int initialStock;

    /** day -> delta (positive for production, negative for consumption) */
    private final Map<Integer, Integer> deltaPerDay = new HashMap<>();

    /** Cached minimum prefix balance across all days. */
    private int minimumPrefixBalance;

    public InventoryTracker(Item item) {
        this.item = Objects.requireNonNull(item);
        this.initialStock = item.getInitialStock();
        this.minimumPrefixBalance = initialStock;
    }

    public Item getItem() {
        return item;
    }

    public void addDelta(int day, int delta) {
        deltaPerDay.merge(day, delta, Integer::sum);
        if (deltaPerDay.get(day) == 0) {
            deltaPerDay.remove(day);
        }
        recomputeMinimumPrefixBalance();
    }

    public void removeDelta(int day, int delta) {
        // removing delta is adding -delta
        addDelta(day, -delta);
    }

    public int getHardScoreContribution() {
        return minimumPrefixBalance >= 0 ? 0 : minimumPrefixBalance;
    }

    private void recomputeMinimumPrefixBalance() {
        int running = initialStock;
        int min = initialStock;

        Integer[] days = deltaPerDay.keySet().toArray(new Integer[0]);
        Arrays.sort(days);
        for (Integer day : days) {
            running += deltaPerDay.get(day);
            if (running < min) {
                min = running;
            }
        }
        minimumPrefixBalance = min;
    }
}