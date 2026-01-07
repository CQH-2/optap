package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

import java.util.function.Function;

/**
 * 约束提供者：定义所有硬约束和软约束
 */
public class SchedulingConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
            // 硬约束
            lineResourceConflict(constraintFactory),
            operationSequence(constraintFactory),
            bomAvailability(constraintFactory),
            capacityLimit(constraintFactory),
            processCapabilityMatching(constraintFactory),
            
            // 软约束
            minimizeTardiness(constraintFactory),
            minimizeChangeover(constraintFactory),
            loadBalancing(constraintFactory)
        };
    }

    // ========== 硬约束 ==========

    /**
     * 硬约束1：生产线互斥 - 同一生产线上的工序时间不能重叠
     */
    Constraint lineResourceConflict(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getAssignedLine() != null && op.getStartTime() != null)
                .join(Operation.class,
                        Joiners.equal(Operation::getAssignedLine),
                        Joiners.lessThan(Operation::getId))
                .filter((op1, op2) -> {
                    if (op2.getStartTime() == null) return false;
                    Long end1 = op1.getEndTime();
                    Long start2 = op2.getStartTime();
                    Long end2 = op2.getEndTime();
                    Long start1 = op1.getStartTime();
                    // 检查时间重叠
                    return !(end1 <= start2 || end2 <= start1);
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("生产线互斥");
    }

    /**
     * 硬约束2：工序顺序 - 同一订单内，后续工序必须在前序工序完成后才能开始
     */
    Constraint operationSequence(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getStartTime() != null && op.getPredecessor() != null)
                .filter(op -> {
                    Operation pred = op.getPredecessor();
                    if (pred.getEndTime() == null) return false;
                    // 后续工序的开始时间必须 >= 前序工序的结束时间
                    return op.getStartTime() < pred.getEndTime();
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("工序顺序");
    }

    /**
     * 硬约束3：BOM齐套性 - 工序开始时，其所需所有物料的可用库存必须充足
     * 简化版本：检查初始库存是否充足
     */
    Constraint bomAvailability(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getStartTime() != null)
                .join(BOMItem.class,
                        Joiners.equal(Function.identity(), BOMItem::getOperation))
                .join(Inventory.class,
                        Joiners.equal((op, bom) -> bom.getMaterial(), Inventory::getMaterial))
                .filter((op, bom, inv) -> {
                    // 检查库存是否充足
                    int required = bom.getQuantity();
                    int available = inv.getAvailableQty();
                    return available < required;
                })
                .penalize(HardSoftScore.ONE_HARD, 
                        (op, bom, inv) -> bom.getQuantity() - inv.getAvailableQty())
                .asConstraint("BOM齐套性");
    }

    /**
     * 硬约束4：产能上限 - 工序在分配的生产线上加工时，其每小时计划产量不能超过maxUnitsPerHour
     */
    Constraint capacityLimit(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getAssignedLine() != null && op.getStartTime() != null)
                .join(LineProcessCapacity.class,
                        Joiners.equal(Operation::getAssignedLine, LineProcessCapacity::getLine),
                        Joiners.equal(Operation::getProcess, LineProcessCapacity::getProcess))
                .filter((op, capacity) -> {
                    // 计算每小时计划产量
                    double unitsPerHour = op.getStandardHours() > 0 ? 
                            op.getQuantity() / op.getStandardHours() : 0;
                    return unitsPerHour > capacity.getMaxUnitsPerHour();
                })
                .penalize(HardSoftScore.ONE_HARD,
                        (op, capacity) -> {
                            double unitsPerHour = op.getStandardHours() > 0 ? 
                                    op.getQuantity() / op.getStandardHours() : 0;
                            return (int)(unitsPerHour - capacity.getMaxUnitsPerHour());
                        })
                .asConstraint("产能上限");
    }

    /**
     * 硬约束5：工艺匹配 - 工序只能分配给具备其process生产能力的生产线
     */
    Constraint processCapabilityMatching(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getAssignedLine() != null && op.getProcess() != null)
                .ifNotExists(LineProcessCapacity.class,
                        Joiners.equal(Operation::getAssignedLine, LineProcessCapacity::getLine),
                        Joiners.equal(Operation::getProcess, LineProcessCapacity::getProcess))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("工艺匹配");
    }

    // ========== 软约束 ==========

    /**
     * 软约束1：最小化总延误 - 惩罚订单完成时间晚于交货期
     */
    Constraint minimizeTardiness(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getEndTime() != null && op.getOrder() != null && op.getOrder().getDueDate() != null)
                .penalize(HardSoftScore.ONE_SOFT,
                        op -> {
                            Long endTime = op.getEndTime();
                            long dueDateMillis = op.getOrder().getDueDate().atStartOfDay()
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                            if (endTime <= dueDateMillis) {
                                return 0; // 未延误，不惩罚
                            }
                            // 延误时间（小时）* 优先级
                            long delayHours = (endTime - dueDateMillis) / (3600 * 1000);
                            return (int)(delayHours * op.getOrder().getPriority());
                        })
                .asConstraint("最小化总延误");
    }

    /**
     * 软约束2：最小化换线时间 - 同一生产线上，加工不同process的工序切换时产生惩罚
     */
    Constraint minimizeChangeover(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getAssignedLine() != null && op.getStartTime() != null)
                .join(Operation.class,
                        Joiners.equal(Operation::getAssignedLine),
                        Joiners.lessThan(Operation::getStartTime))
                .filter((op1, op2) -> {
                    // 找到紧接着的下一个工序
                    if (op2.getStartTime() == null || op1.getEndTime() == null) return false;
                    // op1结束后立即开始op2
                    return Math.abs(op1.getEndTime() - op2.getStartTime()) < 3600 * 1000 &&
                           !op1.getProcess().equals(op2.getProcess());
                })
                .penalize(HardSoftScore.ONE_SOFT, (op1, op2) -> 100)
                .asConstraint("最小化换线时间");
    }

    /**
     * 软约束3：均衡负载 - 尽量避免某些生产线过载而某些闲置
     * 简化版：惩罚使用频率较高的生产线
     */
    Constraint loadBalancing(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Operation.class)
                .filter(op -> op.getAssignedLine() != null && op.getStandardHours() > 0)
                .penalize(HardSoftScore.ONE_SOFT,
                        op -> (int)(op.getStandardHours() * 10)) // 每小时工时惩罚10分
                .asConstraint("均衡负载");
    }
}
