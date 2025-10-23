package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.BomArc;
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
                routerMustBeSupportedByLine(factory),
                componentInventoryNeverNegative(factory),
                minimizeIdle(factory)
        };
    }

    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.from(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Router must be supported by line");
    }

    // 硬约束：子件库存不能为负（单收集器：净库存变动 = 子件累计产出 - 父件累计消耗）
    private Constraint componentInventoryNeverNegative(ConstraintFactory factory) {
        return factory.from(TimeSlot.class)
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
                                    ? (long) a.getProducedQuantity()
                                    : 0L;
                            long parentConsume = arc.getParent().equals(a.getProducedItem())
                                    ? (long) a.getProducedQuantity() * (long) arc.getQuantityPerParent()
                                    : 0L;
                            return childPart - parentConsume;
                        }))
                // inv.initial + net < 0 => 短缺
                .filter((t, arc, inv, net) -> inv.getInitialOnHand() + net < 0L)
                .penalize(HardSoftScore.ONE_HARD,
                        (t, arc, inv, net) -> (int) Math.min(Integer.MAX_VALUE, -(inv.getInitialOnHand() + net)))
                .asConstraint("Component inventory non-negative");
    }

    private Constraint minimizeIdle(ConstraintFactory factory) {
        return factory.from(ProductionAssignment.class)
                .filter(a -> a.getRouter() == null)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Minimize idle slots");
    }
}