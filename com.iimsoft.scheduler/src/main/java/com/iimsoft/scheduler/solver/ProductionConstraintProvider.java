package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.BomArc;
import com.iimsoft.scheduler.domain.ItemInventory;
import com.iimsoft.scheduler.domain.ProductionAssignment;
import com.iimsoft.scheduler.domain.TimeSlot;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.*;

public class ProductionConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                routerMustBeSupportedByLine(factory),
                componentInventoryNeverNegative(factory),
                minimizeIdle(factory)
        };
    }

    // 硬约束：产线必须支持所选工艺
    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.from(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Router must be supported by line");
    }

    private Constraint componentInventoryNeverNegative(ConstraintFactory factory) {
        return factory.from(TimeSlot.class)
                .join(BomArc.class)
                .join(ItemInventory.class, Joiners.equal((t, arc) -> arc.getChild(), ItemInventory::getItem))
                .join(ProductionAssignment.class,
                        Joiners.filtering((t, arc, inv, a) ->
                                a.getRouter() != null &&
                                        a.getTimeSlot().getIndex() <= t.getIndex() &&
                                        (arc.getChild().equals(a.getProducedItem()) ||
                                                arc.getParent().equals(a.getProducedItem()))
                        ))
                .groupBy((t, arc, inv, a) -> t,
                        (t, arc, inv, a) -> arc,
                        (t, arc, inv, a) -> inv,
                        // 子件累计产量
                        ConstraintCollectors.sumLong((t, arc, inv, a) -> arc.getChild().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0),
                        // 父件累计产量
                        ConstraintCollectors.sumLong((t, arc, inv, a) ->
                                arc.getParent().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0))
                .filter((t, arc, inv, childSum, parentSum) ->
                        inv.getInitialOnHand() + childSum < parentSum * arc.getQuantityPerParent())
                .penalize(HardSoftScore.ONE_HARD,
                        (t, arc, inv, childSum, parentSum) ->
                                (int)((parentSum * arc.getQuantityPerParent()) - (inv.getInitialOnHand() + childSum)))
                .asConstraint("Component inventory non-negative");
    }

    // 软约束：减少空闲（鼓励分配工艺）
    private Constraint minimizeIdle(ConstraintFactory factory) {
        return factory.from(ProductionAssignment.class)
                .filter(a -> a.getRouter() == null)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Minimize idle slots");
    }
}