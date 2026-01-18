package com.iimsoft.schduler.score;

import com.iimsoft.schduler.domain.Item;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


/**
 * 单个物料（Item）的库存余额跟踪器（离散时间轴）。
 * <p>
 * 这里用“时间点 -> 库存增量”的方式维护库存，并计算全时间轴上的最小前缀余额：
 * <pre>
 * balance(t) = initialStock + Σ(delta(time) where time <= t)
 * minBalance = min_t(balance(t))
 * </pre>
 * <p>
 * 硬约束扣分贡献：
 * <ul>
 *   <li>若 minBalance >= 0，则贡献 0（无缺料）</li>
 *   <li>否则贡献 minBalance（负数，例如 -3 表示某个时间点缺料 3）</li>
 * </ul>
 * <p>
 * 注意：当前项目的时间单位按“小时”理解，因此这里的 time 实际上是 hour（整数）。
 * 评分器为每个 Item 维护一个 InventoryTracker，并在事件插入/撤销/移动时增量更新。
 */
public class InventoryTracker {

    private final Item item;
    private final int initialStock;

    /** time(hour) -> delta（正数=入库/产出，负数=出库/消耗） */
    private final Map<Integer, Integer> deltaPerTime = new HashMap<>();

    /** 缓存：全时间轴的最小前缀库存余额。 */
    private int minimumPrefixBalance;

    public InventoryTracker(Item item) {
        this.item = Objects.requireNonNull(item);
        this.initialStock = item.getInitialStock();
        this.minimumPrefixBalance = initialStock;
    }

    public Item getItem() {
        return item;
    }

    public void addDelta(int time, int delta) {
        deltaPerTime.merge(time, delta, Integer::sum);
        if (deltaPerTime.get(time) == 0) {
            deltaPerTime.remove(time);
        }
        recomputeMinimumPrefixBalance();
    }

    public void removeDelta(int time, int delta) {
        // 移除 delta 等价于加上 -delta
        addDelta(time, -delta);
    }

    public int getHardScoreContribution() {
        return minimumPrefixBalance >= 0 ? 0 : minimumPrefixBalance;
    }

    private void recomputeMinimumPrefixBalance() {
        int running = initialStock;
        int min = initialStock;

        Integer[] times = deltaPerTime.keySet().toArray(new Integer[0]);
        Arrays.sort(times);
        for (Integer time : times) {
            running += deltaPerTime.get(time);
            if (running < min) {
                min = running;
            }
        }
        minimumPrefixBalance = min;
    }
}