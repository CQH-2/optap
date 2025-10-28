package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

import java.util.*;

/**
 * 增量计分器：
 * - 硬分：全局库存（所有物料、所有时间槽）不得为负；用增量维护每个(物料, 槽)的在手数并累计缺口。
 * - 软分（分桶净额思想的近似实现）：
 *   按物料-到期分桶：
 *   - 比例达成奖励（封顶为桶需求）
 *   - 完全满足且不超3%奖励
 *   - 未满足惩罚
 *   - 超产惩罚（>=3%开始）
 *
 * 说明：
 * - 在本实现中，“达成度”基于该物料“自身产量”的累计，不扣减BOM消耗（与库存硬约束分离），
 *   而BOM子件被父件消耗通过硬约束约束库存非负来保证可行性。
 * - 维护逻辑：当某个 Assignment 的router变化时，仅影响该Assignment对应时间槽之后的
 *   （1）对应物料在手（常量增量）；
 *   （2）其子件在手（常量负增量，按BOM系数）；
 *   因为都是“前缀和”性质，采用“自该槽起对后续槽统一加/减常量”的方式增量更新，并同步调整硬分。
 * - 分桶软分：仅该物料的“到期≥该槽”的桶受当前产量增量影响，逐桶增量更新软分。
 */
public class GlobalInventoryIncrementalScoreCalculator implements IncrementalScoreCalculator<ProductionSchedule, HardSoftScore> {

    // 权重配置（可按需调整）
    private static final int PROP_REWARD_WEIGHT = 20;   // 比例达成奖励权重（基础1000分×权重）
    private static final int COMPLETE_REWARD_BONUS = 3000; // 完全满足且不超3%奖励
    private static final int UNMET_PENALTY_WEIGHT = 2000;  // 未满足惩罚
    private static final int OVER_PENALTY_WEIGHT = 2000;   // 超产惩罚

    // 工作数据
    private ProductionSchedule solution;

    private List<TimeSlot> slots;           // 按 index 升序
    private int slotCount;

    // 初始在手库存
    private Map<Item, Integer> initialOnHand = new HashMap<>();

    // 每物料每槽的“在手库存”（槽结束时）
    private Map<Item, int[]> onHandPerSlot = new HashMap<>();

    // 每物料每槽的“自身产量”（不含BOM消耗）
    private Map<Item, int[]> producedPerSlot = new HashMap<>();

    // BOM：父 -> arcs
    private Map<Item, List<BomArc>> parentToArcs = new HashMap<>();

    // 每物料对应的需求桶（按到期index升序），并缓存前缀需求和、当前达成贡献
    private Map<Item, List<DemandBucket>> demandsByItem = new HashMap<>();

    // 累计分数
    private int hardPenalty; // 缺口总和（>0），最终作为负硬分
    private int softScore;   // 软分（奖励-惩罚）

    @Override
    public void resetWorkingSolution(ProductionSchedule solution) {
        this.solution = solution;

        // 初始化槽序
        this.slots = new ArrayList<>(solution.getTimeSlotList());
        this.slots.sort(Comparator.comparingInt(TimeSlot::getIndex));
        this.slotCount = slots.size();

        // 初始库存
        initialOnHand.clear();
        for (ItemInventory inv : solution.getInventoryList()) {
            initialOnHand.put(inv.getItem(), inv.getInitialOnHand());
        }

        // BOM映射
        parentToArcs.clear();
        for (BomArc arc : solution.getBomArcList()) {
            parentToArcs.computeIfAbsent(arc.getParent(), k -> new ArrayList<>()).add(arc);
        }

        // 初始化 per item 数组
        Set<Item> allItems = collectAllItems(solution);
        producedPerSlot.clear();
        onHandPerSlot.clear();
        for (Item it : allItems) {
            producedPerSlot.put(it, new int[slotCount]);
            onHandPerSlot.put(it, new int[slotCount]); // 稍后一次性计算
        }

        // 累计每槽产量（扫描当前解）
        for (ProductionAssignment a : solution.getAssignmentList()) {
            Router r = a.getRouter();
            if (r == null) continue;
            Item it = r.getItem();
            int s = a.getTimeSlot().getIndex();
            producedPerSlot.get(it)[s] += r.getSpeedPerHour();
        }

        // 一次性计算全局库存（先每槽入库，再同槽由父件消耗子件，然后前缀累加）
        // 实际这里采用“前缀形式”：初始在手 -> 遍历槽 i：先加本槽产量，后减本槽因父件导致的子件消耗。
        hardPenalty = 0;
        for (Item it : allItems) {
            int[] onHands = onHandPerSlot.get(it);
            int cur = initialOnHand.getOrDefault(it, 0);
            for (int s = 0; s < slotCount; s++) {
                // 入库：本物料本槽产量
                cur += producedPerSlot.get(it)[s];
                // 消耗：由“父件”在本槽产出导致该物料（若是其子件）被消耗
                int consumed = 0;
                // 遍历所有父件arc，看该物料是否为其子件
                for (Map.Entry<Item, List<BomArc>> e : parentToArcs.entrySet()) {
                    for (BomArc arc : e.getValue()) {
                        if (arc.getChild().equals(it)) {
                            int parentQty = producedPerSlot.get(arc.getParent())[s];
                            if (parentQty != 0) {
                                consumed += parentQty * arc.getQuantityPerParent();
                            }
                        }
                    }
                }
                cur -= consumed;

                onHands[s] = cur;
                if (cur < 0) {
                    hardPenalty += -cur;
                }
            }
        }

        // 构建分桶缓存，并计算软分
        buildDemandBuckets();
        softScore = 0;
        for (Map.Entry<Item, List<DemandBucket>> e : demandsByItem.entrySet()) {
            for (DemandBucket db : e.getValue()) {
                softScore += db.recomputeContribution();
            }
        }
    }

    @Override
    public void beforeEntityAdded(Object entity) { }

    @Override
    public void afterEntityAdded(Object entity) { }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        if (!(entity instanceof ProductionAssignment) || !"router".equals(variableName)) return;
        ProductionAssignment a = (ProductionAssignment) entity;
        Router old = a.getRouter();
        if (old == null) return;

        applyAssignmentDelta(a.getTimeSlot().getIndex(), old.getItem(), -old.getSpeedPerHour());
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        if (!(entity instanceof ProductionAssignment) || !"router".equals(variableName)) return;
        ProductionAssignment a = (ProductionAssignment) entity;
        Router now = a.getRouter();
        if (now == null) return;

        applyAssignmentDelta(a.getTimeSlot().getIndex(), now.getItem(), +now.getSpeedPerHour());
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
        if (!(entity instanceof ProductionAssignment)) return;
        ProductionAssignment a = (ProductionAssignment) entity;
        Router r = a.getRouter();
        if (r == null) return;
        applyAssignmentDelta(a.getTimeSlot().getIndex(), r.getItem(), -r.getSpeedPerHour());
    }

    @Override
    public void afterEntityRemoved(Object entity) { }

    @Override
    public HardSoftScore calculateScore() {
        return HardSoftScore.of(-hardPenalty, softScore);
    }

    // -------------- 关键增量更新逻辑 --------------

    private void applyAssignmentDelta(int slotIndex, Item item, int qtyDelta) {
        if (qtyDelta == 0) return;

        // 1) 更新 producedPerSlot[item][slotIndex]
        producedPerSlot.get(item)[slotIndex] += qtyDelta;

        // 2) 影响“库存前缀和”：自slotIndex起，item的在手 +qtyDelta
        applyOnHandLinearDelta(item, slotIndex, qtyDelta);

        // 3) 影响子件的在手：对每个 child，自slotIndex起，child的在手 -(qtyDelta * arc.qty)
        for (BomArc arc : parentToArcs.getOrDefault(item, Collections.emptyList())) {
            Item child = arc.getChild();
            int childDelta = -qtyDelta * arc.getQuantityPerParent();
            if (childDelta != 0) {
                applyOnHandLinearDelta(child, slotIndex, childDelta);
            }
        }

        // 4) 分桶软分：仅该物料的分桶受影响（到期索引 >= slotIndex 的桶）
        List<DemandBucket> buckets = demandsByItem.getOrDefault(item, Collections.emptyList());
        for (DemandBucket db : buckets) {
            if (db.dueIndex >= slotIndex) {
                // 先移除旧贡献
                softScore -= db.cachedContribution;
                // 增量更新该桶的“自身累计产量”
                db.producedCumAtDue += qtyDelta;
                // 重新计算贡献并累加
                softScore += db.recomputeContribution();
            }
        }
    }

    // 自某槽起对所有后续槽统一加/减一个常量，并同步维护硬分缺口
    private void applyOnHandLinearDelta(Item item, int fromSlot, int delta) {
        int[] onHands = onHandPerSlot.get(item);
        for (int s = fromSlot; s < slotCount; s++) {
            int old = onHands[s];
            int newVal = old + delta;

            // 硬分调整：缺口为负数取反累加
            int oldDef = Math.max(0, -old);
            int newDef = Math.max(0, -newVal);
            hardPenalty += (newDef - oldDef);

            onHands[s] = newVal;
        }
    }

    // -------------- 分桶缓存构建 --------------

    private void buildDemandBuckets() {
        demandsByItem.clear();

        // 1) 将需求按 item 分组并按 dueIndex 升序
        Map<Item, List<DemandOrder>> grouped = new HashMap<>();
        for (DemandOrder d : solution.getDemandList()) {
            grouped.computeIfAbsent(d.getItem(), k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<Item, List<DemandOrder>> e : grouped.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(DemandOrder::getDueTimeSlotIndex));
        }

        // 2) 对每个 item 建桶，并计算“之前桶需求和(prevSum)”
        for (Map.Entry<Item, List<DemandOrder>> e : grouped.entrySet()) {
            Item item = e.getKey();
            List<DemandOrder> ds = e.getValue();

            List<DemandBucket> buckets = new ArrayList<>();
            int prevSum = 0;
            for (DemandOrder d : ds) {
                int dueIdx = d.getDueTimeSlotIndex();
                int producedCum = sumProducedUpTo(item, dueIdx); // 自身产量累计
                DemandBucket db = new DemandBucket(item, d.getQuantity(), dueIdx, prevSum, producedCum);
                db.recomputeContribution(); // 初始化缓存
                buckets.add(db);
                prevSum += d.getQuantity();
            }
            demandsByItem.put(item, buckets);
        }
    }

    private int sumProducedUpTo(Item item, int dueIndex) {
        int[] arr = producedPerSlot.get(item);
        int sum = 0;
        int end = Math.min(dueIndex, arr.length - 1);
        for (int i = 0; i <= end; i++) {
            sum += arr[i];
        }
        return sum;
    }

    private Set<Item> collectAllItems(ProductionSchedule solution) {
        LinkedHashSet<Item> set = new LinkedHashSet<>();
        for (ItemInventory inv : solution.getInventoryList()) set.add(inv.getItem());
        for (Router r : solution.getRouterList()) set.add(r.getItem());
        for (BomArc arc : solution.getBomArcList()) {
            set.add(arc.getParent());
            set.add(arc.getChild());
        }
        for (DemandOrder d : solution.getDemandList()) set.add(d.getItem());
        return set;
    }

    // -------------- 分桶结构（含缓存） --------------
    private static class DemandBucket {
        final Item item;
        final int demand;
        final int dueIndex;
        final int prevDemandSum;      // 之前桶需求和（净额用）
        int producedCumAtDue;         // 截止到期时“自身累计产量”
        int cachedContribution;       // 该桶当前对软分的总贡献（奖励-惩罚）

        DemandBucket(Item item, int demand, int dueIndex, int prevDemandSum, int producedCumAtDue) {
            this.item = item;
            this.demand = demand;
            this.dueIndex = dueIndex;
            this.prevDemandSum = prevDemandSum;
            this.producedCumAtDue = producedCumAtDue;
        }

        int recomputeContribution() {
            // available = 自身累计产量 - 之前桶需求和（净额）
            int available = Math.max(0, producedCumAtDue - prevDemandSum);

            // 比例奖励（封顶）
            int propReward = demand <= 0 ? 0 : ((Math.min(available, demand) * 1000) / demand) * PROP_REWARD_WEIGHT;

            // 完全满足奖励（不超3%）
            int maxAcceptable = (int) Math.ceil(demand * 1.03);
            int completeReward = (available >= demand && available <= maxAcceptable) ? COMPLETE_REWARD_BONUS : 0;

            // 未满足惩罚
            int unmet = Math.max(0, demand - available);
            int unmetPenalty = unmet * UNMET_PENALTY_WEIGHT;

            // 超产惩罚（>=3%开始）
            int over = Math.max(0, available - demand);
            int tolerated = Math.max(0, (int) Math.ceil(demand * 0.03) - 1);
            int overPenalty = Math.max(0, over - tolerated) * OVER_PENALTY_WEIGHT;

            cachedContribution = propReward + completeReward - unmetPenalty - overPenalty;
            return cachedContribution;
        }
    }
}