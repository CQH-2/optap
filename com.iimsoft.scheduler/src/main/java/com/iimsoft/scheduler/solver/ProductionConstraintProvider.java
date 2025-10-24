package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;

import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.*;

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
        return factory.from(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null && !a.getLine().supports(a.getRouter()))
                .penalize(HardSoftScore.ofHard(10))
                .asConstraint("Router must be supported by line");
    }

    // 硬约束：任意时间点，子件累计产出 >= 父件累计产出 * 用量
//    private Constraint bomChildrenCumulativeSufficient(ConstraintFactory factory) {
//        // 锚定为每个时间槽（作为累计的“截点”）与每条 BOM 边的组合
//        return factory.from(TimeSlot.class)
//                .join(BomArc.class, Joiners.filtering((t, arc) -> true))
//                // 汇总到该时间槽（含）之前的所有产出（父/子件）
//                .join(ProductionAssignment.class,
//                        Joiners.filtering((t, arc, a) ->
//                                a.getRouter() != null &&
//                                        a.getTimeSlot().getIndex() <= t.getIndex() &&
//                                        (arc.getChild().equals(a.getProducedItem()) ||
//                                                arc.getParent().equals(a.getProducedItem()))
//                        ))
//                .groupBy((t, arc, a) -> t,
//                        (t, arc, a) -> arc,
//                        // 子件累计产量
//                        ConstraintCollectors.sumLong((t, arc, a) ->
//                                arc.getChild().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0),
//                        // 父件累计产量
//                        ConstraintCollectors.sumLong((t, arc, a) ->
//                                arc.getParent().equals(a.getProducedItem()) ? a.getProducedQuantity() : 0))
//                .filter((t, arc, childSum, parentSum) -> childSum < parentSum * arc.getQuantityPerParent())
//                .penalize(HardSoftScore.ONE_HARD,
//                        (t, arc, childSum, parentSum) -> (int) (parentSum * arc.getQuantityPerParent() - childSum))
//                .asConstraint("BOM children cumulative sufficient");
//    }

    // 软约束：按到期满足需求（未满足数量按软分惩罚）
//    private Constraint fulfillDemandByDue(ConstraintFactory factory) {
//        return factory.from(DemandOrder.class)
//                .join(ProductionAssignment.class,
//                        Joiners.filtering((d, a) ->
//                                a.getRouter() != null &&
//                                        d.getItem().equals(a.getProducedItem()) &&
//                                        a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
//                .groupBy(d -> d, ConstraintCollectors.sumLong((d, a) -> a.getProducedQuantity()))
//                .filter((d, producedSum) -> producedSum < d.getQuantity())
//                .penalize(HardSoftScore.ONE_SOFT, (d, producedSum) -> (int) (d.getQuantity() - producedSum))
//                .asConstraint("Demand fulfillment by due date");
//    }

    private Constraint meetDemandByDueDate(ConstraintFactory factory) {
        return factory.forEach(DemandOrder.class)
                .join(ProductionAssignment.class,
                        Joiners.filtering(
                                (d, a) -> a.getRouter() != null && d.getItem().equals(a.getProducedItem()) && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex()))
                .groupBy((d, a) -> d, ConstraintCollectors.sum((d, a) -> a.getProducedQuantity()))
                .filter((d, producedSum) -> producedSum < d.getQuantity())
                .penalize(HardSoftScore.ONE_HARD, (d, producedSum) -> (d.getQuantity() - producedSum) * 10)
                .asConstraint("交货日期前满足需求");
    }

    // 软约束：减少空闲（鼓励分配工艺）
    private Constraint minimizeIdle(ConstraintFactory factory) {
        return factory.forEach(ProductionAssignment.class)
                .filter(a -> a.getRouter() != null)
                .reward(HardSoftScore.ONE_SOFT)
                .asConstraint("减少空闲时间");
    }

}