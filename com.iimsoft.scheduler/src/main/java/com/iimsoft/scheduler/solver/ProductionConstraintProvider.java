package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

public class ProductionConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // 硬约束
                routerMustBeSupportedByLine(factory),
                inventoryBalanceNonNegative(factory),

                // 软约束（正向引导与惩罚）
                rewardDemandSatisfactionCapped(factory), // 主目标：按需产出（封顶净需求）
                penalizeOverProduction(factory),         // 软：超产惩罚
                rewardJustInTimeAffinity(factory),       // 临近交期的产出更有奖励（但始终为正）
                rewardBatchingSameRouter(factory),       // 相邻时段保持同工艺（减少切换）
                discourageNonDemandedProduction(factory) // 无任何需求关联的产出给一点点负引导
        };
    }

    // 硬约束：产线必须支持所选工艺
    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ofHard(1000))
                .asConstraint("Router must be supported by line");
    }

    // 硬约束：子件库存余额不能为负（含初始库存）
    private Constraint inventoryBalanceNonNegative(ConstraintFactory factory) {
        return factory.forEach(TimeSlot.class)
                .join(BomArc.class)
                .join(ProductionAssignment.class,
                        Joiners.filtering((t, arc, a) ->
                                a.getProducedItem() != null &&
                                        a.getTimeSlot().getIndex() <= t.getIndex() &&
                                        (arc.getChild().equals(a.getProducedItem()) || arc.getParent().equals(a.getProducedItem()))
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
                        inv.getInitialOnHand() + tuple.getChildSum() <
                                tuple.getParentSum() * tuple.getArc().getQuantityPerParent())
                .penalize(HardSoftScore.ofHard(1000),
                        (tuple, inv) ->
                                tuple.getParentSum() * tuple.getArc().getQuantityPerParent()
                                        - (inv.getInitialOnHand() + tuple.getChildSum()))
                .asConstraint("子件库存余额不能为负（含初始库存）");
    }

    // 主目标：按需产出奖励（到交期前，封顶“净需求”）
    private Constraint rewardDemandSatisfactionCapped(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null &&
                                        a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .reward(HardSoftScore.ofSoft(10),(d, producedSum) -> Math.min(producedSum, d.getQuantity()))
                .asConstraint("按需产出奖励（到期前，封顶净需求）");
    }

    // 软约束：超产惩罚（对超出需求的产量给予负分，权重高于主目标）
    private Constraint penalizeOverProduction(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null &&
                                        a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .penalize(HardSoftScore.ofSoft(100), (d, producedSum) -> Math.max(0, producedSum - d.getQuantity()))
                .asConstraint("超产惩罚");
    }

    // JIT亲和奖励（贴近交期分数，辅助目标）
    private Constraint rewardJustInTimeAffinity(ConstraintFactory factory) {
        final int maxBonus = 4;
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getRouter() != null &&
                                        a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .reward(HardSoftScore.ofSoft(2), (d, a) -> {
                    int slack = d.getDueTimeSlotIndex() - a.getTimeSlot().getIndex();
                    int proximity = Math.max(1, maxBonus - slack);
                    return a.getProducedQuantity() * proximity;
                })
                .asConstraint("近交期优先（JIT亲和）");
    }

    // 批量同工艺奖励（细化排产，低权重）
    private Constraint rewardBatchingSameRouter(ConstraintFactory factory) {
        return factory.forEachUniquePair(
                        ProductionAssignment.class,
                        Joiners.equal(ProductionAssignment::getLine),
                        Joiners.lessThan(a -> a.getTimeSlot().getIndex()))
                .filter((a1, a2) ->
                        a1.getRouter() != null &&
                                a1.getRouter().equals(a2.getRouter()) &&
                                a2.getTimeSlot().getIndex() == a1.getTimeSlot().getIndex() + 1)
                .reward(HardSoftScore.ofSoft(1))
                .asConstraint("相邻时段保持同工艺（减少切换）");
    }

    // 无需求产出惩罚（防止产能浪费，权重适中）
    private Constraint discourageNonDemandedProduction(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null)
                .ifNotExists(DemandOrder.class, Joiners.equal(ProductionAssignment::getProducedItem, DemandOrder::getItem))
                .penalize(HardSoftScore.ofSoft(5), ProductionAssignment::getProducedQuantity)
                .asConstraint("无需求的生产不鼓励");
    }
}