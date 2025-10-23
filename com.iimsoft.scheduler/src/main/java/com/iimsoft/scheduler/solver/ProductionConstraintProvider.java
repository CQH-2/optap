package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.BomArc;
import com.iimsoft.scheduler.domain.DemandOrder;
import com.iimsoft.scheduler.domain.ItemInventory;
import com.iimsoft.scheduler.domain.ProductionAssignment;
import com.iimsoft.scheduler.domain.TimeSlot;
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
                // 硬：产线能力
                routerMustBeSupportedByLine(factory),
                // 硬：子件库存不为负
                componentInventoryNeverNegative(factory),
                // 硬：按交期满足需求（驱动必须生产）
                meetDemandByDueDate(factory),

                // 软：没有任何需求的物料尽量少产（抑制无意义堆库存）
                discourageProductionWithoutDemand(factory),
                // 软：减少空闲（如不希望为减少空闲而过产，可暂时注释掉）
                minimizeIdle(factory)
        };
    }

    // 硬约束：产线必须支持所选工艺
    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Router must be supported by line");
    }

    // 硬约束：子件库存不能为负（净库存变动 = 子件累计产出 - 父件累计消耗）
    private Constraint componentInventoryNeverNegative(ConstraintFactory factory) {
        return factory.forEach(TimeSlot.class)
                .join(BomArc.class)
                .join(ItemInventory.class) // 无条件 join，再过滤匹配的子件库存
                .filter((t, arc, inv) -> arc.getChild().equals(inv.getItem()))
                .join(ProductionAssignment.class,
                        Joiners.filtering((t, arc, inv, a) ->
                                a.getRouter() != null &&
                                        a.getTimeSlot().getIndex() <= t.getIndex() &&
                                        (arc.getChild().equals(a.getProducedItem()) ||
                                                arc.getParent().equals(a.getProducedItem()))
                        ))
                // 按 (t, arc, inv) 聚合一个“净库存变动”：
                // net = sum(childProduced) - sum(parentProduced * quantityPerParent)
                .groupBy((t, arc, inv, a) -> t,
                        (t, arc, inv, a) -> arc,
                        (t, arc, inv, a) -> inv,
                        ConstraintCollectors.sumLong((t, arc, inv, a) -> {
                            long childPart = arc.getChild().equals(a.getProducedItem())
                                    ? (long) a.getProducedQuantity() : 0L;
                            long parentConsume = arc.getParent().equals(a.getProducedItem())
                                    ? (long) a.getProducedQuantity() * (long) arc.getQuantityPerParent() : 0L;
                            return childPart - parentConsume;
                        }))
                // inv.initial + net < 0 => 短缺
                .filter((t, arc, inv, net) -> inv.getInitialOnHand() + net < 0L)
                .penalize(HardSoftScore.ONE_HARD,
                        (t, arc, inv, net) -> (int) Math.min(Integer.MAX_VALUE, -(inv.getInitialOnHand() + net)))
                .asConstraint("Component inventory non-negative");
    }

    // 硬约束：到期前累计产出 ≥ 需求（驱动必须生产）
    private Constraint meetDemandByDueDate(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class, Joiners.filtering((d, a) -> a.getRouter() != null && d.getItem().equals(a.getProducedItem()) && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy((d, a) -> d, ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .filter((d, producedSum) -> producedSum < d.getQuantity())
                .penalize(HardSoftScore.ONE_HARD, (d, producedSum) -> (d.getQuantity() - producedSum) * 10)
                .asConstraint("Meet demand by due date");
    }

    // 软约束：没有任何需求的物料尽量少产
    private Constraint discourageProductionWithoutDemand(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null)
                .ifNotExists(DemandOrder.class,
                        Joiners.equal(ProductionAssignment::getProducedItem, DemandOrder::getItem))
                .penalize(HardSoftScore.ONE_SOFT, ProductionAssignment::getProducedQuantity)
                .asConstraint("Discourage production without demand");
    }

    // 软约束：减少空闲
    private Constraint minimizeIdle(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() == null)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Minimize idle slots");
    }
}