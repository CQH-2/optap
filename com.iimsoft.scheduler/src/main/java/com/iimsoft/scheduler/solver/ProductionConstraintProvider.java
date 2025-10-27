package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;

import org.drools.tms.beliefsystem.defeasible.Join;
import org.optaplanner.constraint.streams.bavet.quad.BavetFlattenLastQuadConstraintStream;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.*;
import org.optaplanner.core.api.score.stream.quad.QuadConstraintBuilder;
import org.optaplanner.core.api.score.stream.quad.QuadConstraintStream;
import org.optaplanner.core.api.score.stream.quad.QuadJoiner;
import org.optaplanner.core.impl.util.Quadruple;

public class ProductionConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                routerMustBeSupportedByLine(factory),
//                bomChildrenCumulativeSufficient(factory),
                meetDemandByDueDate(factory),
                minimizeIdle(factory)
        };
    }

    // 硬约束：产线必须支持所选工艺
    private Constraint routerMustBeSupportedByLine(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ofHard(1000))
                .asConstraint("Router must be supported by line");
    }


    private Constraint meetDemandByDueDate(ConstraintFactory factory) {
       return factory.forEach(ProductionAssignment.class)
                .filter(a->a.getProducedItem() != null)
                .join(DemandOrder.class,Joiners.filtering((a,b)->b.getItem().equals(a.getProducedItem())&&a.getTimeSlot().getIndex()<=b.getDueTimeSlotIndex()))
                .groupBy((a,b)->b, ConstraintCollectors.sum((a,b)->a.getProducedQuantity()))
                .filter((b,producedSum)->producedSum>=b.getQuantity())
                .penalize(HardSoftScore.ONE_HARD,(b,producedSum)->(b.getQuantity()-producedSum))
                .asConstraint("交货日期前不满足需求");

    }



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
                        // 分组键：时间槽、BOM 边
                        (t, arc, a) -> t,
                        (t, arc, a) -> arc,
                        // 子件累计产量
                        ConstraintCollectors.sum((t, arc, a) ->
                                arc.getChild().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0),
                        // 父件累计产量
                        ConstraintCollectors.sum((t, arc, a) ->
                                arc.getParent().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0)
                )
                // 用record封装
                .map(InventoryBalanceTuple::new)
                // 在分组后再把子件初始库存 join 进来
                .join(ItemInventory.class, Joiners.equal(tuple -> tuple.arc.getChild(), ItemInventory::getItem))
                // 余额判定
                .filter((tuple, inv) -> inv.getInitialOnHand() + tuple.getChildSum() < tuple.getParentSum() * tuple.arc.getQuantityPerParent())
                .penalize(HardSoftScore.ONE_HARD,
                        (tuple, inv) -> tuple.parentSum * tuple.arc.getQuantityPerParent() - (inv.getInitialOnHand() + tuple.childSum))
                .asConstraint("子件库存余额不能为负（含初始库存）");
    }

    // 软约束：减少空闲（鼓励分配工艺）
    private Constraint minimizeIdle(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null)
                .reward(HardSoftScore.ONE_SOFT)
                .asConstraint("减少空闲时间");
    }

}