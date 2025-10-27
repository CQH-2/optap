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
                routerMustBeSupportedByLine(factory),   // 硬：产线能力
                inventoryBalanceNonNegative(factory),   // 硬：子件库存余额非负
                rewardDemandSatisfaction(factory)       // 软：按需产出奖励（核心正向约束）
                // 可按需再加其他正向目标（如按期权重、优先级等）
        };
    }

    // 硬约束：产线必须支持所选工艺
    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ofHard(1000))
                .asConstraint("Router must be supported by line");
    }

    // 软约束：按需产出奖励（到交期前，封顶“净需求”）
    // 说明：
    // - App 已将 DemandOrder.quantity 设置为“净需求”（总需求 - 初始库存），因此这里不再叠加初始库存。
    // - 奖励 = min(截止前累计产量, 净需求)，避免超产也被奖励。
    private Constraint rewardDemandSatisfaction(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.equal(DemandOrder::getItem, ProductionAssignment::getProducedItem),
                        Joiners.filtering((d, a) ->
                                a.getProducedItem() != null &&
                                        a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy(
                        (d, a) -> d,
                        ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .reward(HardSoftScore.ONE_SOFT,
                        (d, producedSum) -> Math.min(producedSum, d.getQuantity()))
                .asConstraint("按需产出奖励（到期前，封顶净需求）");
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
                        // 子件累计产量
                        ConstraintCollectors.sum((t, arc, a) ->
                                arc.getChild().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0),
                        // 父件累计产量
                        ConstraintCollectors.sum((t, arc, a) ->
                                arc.getParent().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0)
                )
                .map(InventoryBalanceTuple::new)
                .join(ItemInventory.class, Joiners.equal(tuple -> tuple.getArc().getChild(), ItemInventory::getItem))
                .filter((tuple, inv) ->
                        inv.getInitialOnHand() + tuple.getChildSum() <
                                tuple.getParentSum() * tuple.getArc().getQuantityPerParent())
                .penalize(HardSoftScore.ONE_HARD,
                        (tuple, inv) ->
                                tuple.getParentSum() * tuple.getArc().getQuantityPerParent()
                                        - (inv.getInitialOnHand() + tuple.getChildSum()))
                .asConstraint("子件库存余额不能为负（含初始库存）");
    }

}