package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

/**
 * 约束设计（中文注释）：
 * - 硬约束：确保解可行
 *   1) 工艺必须被产线支持
 *   2) 子件齐套性（库存余额不能为负）
 *
 * - 软约束：优化目标（“奖+罚”并存）
 *   1) 按桶（同一物料分多日交付）按比例奖励：基础分1000，缺口越少加分越多；超产不加额外分（封顶）
 *   2) 完全满足且不超产3%（每个交付桶）给予大额奖励
 *   3) 超产达到或超过3%（按桶净额）开始处罚（在3%以内不罚）
 *   4) 生产线相邻时段切换工艺惩罚（防止频繁换工艺）
 *
 * 说明：
 * - “按桶净额”逻辑：对每条 Demand（同一物料不同到期日的一条需求称为一个“交付桶”），
 *   只统计“到该桶到期为止的总产量 - 之前桶的总需求量”的净可用产量，再与本桶需求比较做奖罚。
 *   这样避免早期产量被后续日期的需求重复计入奖励或惩罚。
 */
public class ProductionConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // 硬约束（必须满足）
                routerMustBeSupportedByLine(factory),
                inventoryBalanceNonNegative(factory),

                // 软约束（按桶净额：奖励达成度）
                rewardDemandSatisfactionFirstBucket(factory),
                rewardDemandSatisfactionLaterBuckets(factory),

                // 软约束（完全满足且不超产3%：大额奖励）
                rewardDemandCompleteFirstBucket(factory),
                rewardDemandCompleteLaterBuckets(factory),

                // 软约束（超产达到或超过3%开始罚：按桶净额）
                penalizeOverProductionFirstBucket(factory),
                penalizeOverProductionLaterBuckets(factory),

                // 软约束（相邻时段切换工艺惩罚）
                penalizeRouterChange(factory)
        };
    }

    // =========================
    // 硬约束（必须）
    // =========================

    // 硬约束：产线必须支持所选工艺（不支持 => 大罚）
    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ofHard(1000))
                .asConstraint("硬-工艺必须被产线支持");
    }

    // 硬约束：子件库存余额不能为负（考虑初始库存；累计到当前时段）
    private Constraint inventoryBalanceNonNegative(ConstraintFactory factory) {
        return factory.forEach(TimeSlot.class)
                .join(BomArc.class)
                .join(ProductionAssignment.class,
                        Joiners.filtering((t, arc, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= t.getIndex()
                                        && (arc.getChild().equals(a.getProducedItem())
                                        || arc.getParent().equals(a.getProducedItem()))
                        ))
                .groupBy(
                        (t, arc, a) -> t,
                        (t, arc, a) -> arc,
                        ConstraintCollectors.sum((t, arc, a) ->
                                arc.getChild().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0),
                        ConstraintCollectors.sum((t, arc, a) ->
                                arc.getParent().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0)
                )
                .map(InventoryBalanceTuple::new)
                .join(ItemInventory.class, Joiners.equal(tuple -> tuple.getArc().getChild(), ItemInventory::getItem))
                .filter((tuple, inv) ->
                        inv.getInitialOnHand() + tuple.getChildSum()
                                < tuple.getParentSum() * tuple.getArc().getQuantityPerParent())
                .penalize(HardSoftScore.ofHard(1000),
                        (tuple, inv) ->
                                tuple.getParentSum() * tuple.getArc().getQuantityPerParent()
                                        - (inv.getInitialOnHand() + tuple.getChildSum()))
                .asConstraint("硬-子件库存余额不能为负（含初始库存）");
    }

    // =========================
    // 软约束（按桶净额：奖励达成度，基础分1000）
    // =========================

    // 首桶：本物料没有更早到期的桶 => 净可用=到期前总产量
    private Constraint rewardDemandSatisfactionFirstBucket(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .ifNotExists(DemandOrder.class,
                        Joiners.equal((d, producedSum) -> d.getItem(), DemandOrder::getItem),
                        Joiners.lessThan((d, producedSum) -> d.getDueTimeSlotIndex(), DemandOrder::getDueTimeSlotIndex))
                // 基础分=1000，按比例奖励（封顶不超奖）；权重=1 => 最大加1000分
                .reward(HardSoftScore.ofSoft(1), (d, producedSum) -> {
                    int demand = d.getQuantity();
                    if (demand <= 0) return 0;
                    int available = Math.max(0, producedSum);
                    int satisfied = Math.min(available, demand);
                    return (satisfied * 1000) / demand;
                })
                .asConstraint("软-按桶比例奖励-首桶（基础分1000）");
    }

    // 后续桶：有更早到期的桶 => 净可用=到期前总产量-之前桶需求和
    private Constraint rewardDemandSatisfactionLaterBuckets(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .join(DemandOrder.class,
                        Joiners.equal((d, producedSum) -> d.getItem(), DemandOrder::getItem),
                        Joiners.lessThan((d, producedSum) -> d.getDueTimeSlotIndex(), DemandOrder::getDueTimeSlotIndex))
                .groupBy(
                        (d, producedSum, prev) -> d,
                        (d, producedSum, prev) -> producedSum,
                        ConstraintCollectors.sum((d, producedSum, prev) -> prev.getQuantity()))
                .reward(HardSoftScore.ofSoft(1), (d, producedSum, prevSum) -> {
                    int demand = d.getQuantity();
                    if (demand <= 0) return 0;
                    int available = Math.max(0, producedSum - prevSum);
                    int satisfied = Math.min(available, demand);
                    return (satisfied * 1000) / demand;
                })
                .asConstraint("软-按桶比例奖励-后续桶（基础分1000）");
    }

    // =========================
    // 软约束（完全满足且不超产3%：大额奖励）
    // =========================

    // 首桶：available ∈ [demand, ceil(demand*1.03)] => 额外大额奖励
    private Constraint rewardDemandCompleteFirstBucket(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .ifNotExists(DemandOrder.class,
                        Joiners.equal((d, producedSum) -> d.getItem(), DemandOrder::getItem),
                        Joiners.lessThan((d, producedSum) -> d.getDueTimeSlotIndex(), DemandOrder::getDueTimeSlotIndex))
                .filter((d, producedSum) -> {
                    int demand = d.getQuantity();
                    int available = Math.max(0, producedSum);
                    int maxAcceptable = (int) Math.ceil(demand * 1.03);
                    return available >= demand && available <= maxAcceptable;
                })
                .reward(HardSoftScore.ofSoft(1000), (d, producedSum) -> 1)
                .asConstraint("软-按桶完全满足奖励-首桶（不超产3%）");
    }

    // 后续桶：available ∈ [demand, ceil(demand*1.03)]（使用净可用）
    private Constraint rewardDemandCompleteLaterBuckets(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .join(DemandOrder.class,
                        Joiners.equal((d, producedSum) -> d.getItem(), DemandOrder::getItem),
                        Joiners.lessThan((d, producedSum) -> d.getDueTimeSlotIndex(), DemandOrder::getDueTimeSlotIndex))
                .groupBy(
                        (d, producedSum, prev) -> d,
                        (d, producedSum, prev) -> producedSum,
                        ConstraintCollectors.sum((d, producedSum, prev) -> prev.getQuantity()))
                .filter((d, producedSum, prevSum) -> {
                    int demand = d.getQuantity();
                    int available = Math.max(0, producedSum - prevSum);
                    int maxAcceptable = (int) Math.ceil(demand * 1.03);
                    return available >= demand && available <= maxAcceptable;
                })
                .reward(HardSoftScore.ofSoft(1000), (d, producedSum, prevSum) -> 1)
                .asConstraint("软-按桶完全满足奖励-后续桶（不超产3%）");
    }

    // =========================
    // 软约束（超产达到或超过3%开始处罚：按桶净额）
    // =========================

    // 首桶：available 超出 demand 的容忍阈值（>=3%）开始罚
    private Constraint penalizeOverProductionFirstBucket(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .ifNotExists(DemandOrder.class,
                        Joiners.equal((d, producedSum) -> d.getItem(), DemandOrder::getItem),
                        Joiners.lessThan((d, producedSum) -> d.getDueTimeSlotIndex(), DemandOrder::getDueTimeSlotIndex))
                .penalize(HardSoftScore.ofSoft(1000), (d, producedSum) -> {
                    int demand = d.getQuantity();
                    int available = Math.max(0, producedSum);
                    int over = Math.max(0, available - demand);
                    int tolerated = (int) Math.ceil(demand * 0.03) - 1; // <3% 不罚；>=3% 才罚
                    tolerated = Math.max(0, tolerated);
                    return Math.max(0, over - tolerated);
                })
                .asConstraint("软-按桶超产惩罚-首桶（>=3%开始处罚）");
    }

    // 后续桶：使用净可用（扣除前桶需求）来判断超产
    private Constraint penalizeOverProductionLaterBuckets(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null
                                        && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .join(DemandOrder.class,
                        Joiners.equal((d, producedSum) -> d.getItem(), DemandOrder::getItem),
                        Joiners.lessThan((d, producedSum) -> d.getDueTimeSlotIndex(), DemandOrder::getDueTimeSlotIndex))
                .groupBy(
                        (d, producedSum, prev) -> d,
                        (d, producedSum, prev) -> producedSum,
                        ConstraintCollectors.sum((d, producedSum, prev) -> prev.getQuantity()))
                .penalize(HardSoftScore.ofSoft(1000), (d, producedSum, prevSum) -> {
                    int demand = d.getQuantity();
                    int available = Math.max(0, producedSum - prevSum);
                    int over = Math.max(0, available - demand);
                    int tolerated = (int) Math.ceil(demand * 0.03) - 1;
                    tolerated = Math.max(0, tolerated);
                    return Math.max(0, over - tolerated);
                })
                .asConstraint("软-按桶超产惩罚-后续桶（>=3%开始处罚）");
    }

    // =========================
    // 软约束（相邻时段切换工艺惩罚）
    // =========================

    // 同一条产线相邻时间槽如果切换工艺，则轻度惩罚，避免频繁切换
    private Constraint penalizeRouterChange(ConstraintFactory factory) {
        return factory.forEachUniquePair(
                        ProductionAssignment.class,
                        Joiners.equal(ProductionAssignment::getLine),
                        Joiners.lessThan(a -> a.getTimeSlot().getIndex()))
                .filter((a1, a2) ->
                        a1.getRouter() != null
                                && a2.getRouter() != null
                                && a2.getTimeSlot().getIndex() == a1.getTimeSlot().getIndex() + 1
                                && !a1.getRouter().equals(a2.getRouter()))
                .penalize(HardSoftScore.ofSoft(5))
                .asConstraint("软-相邻时段切换工艺惩罚");
    }
}