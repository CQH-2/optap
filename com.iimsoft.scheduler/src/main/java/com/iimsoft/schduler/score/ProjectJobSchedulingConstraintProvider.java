package com.iimsoft.schduler.score;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

import com.iimsoft.schduler.domain.Allocation;
import com.iimsoft.schduler.domain.InventoryEvent;
import com.iimsoft.schduler.domain.JobType;
import com.iimsoft.schduler.domain.ResourceRequirement;

/**
 * 基于 Constraint Streams（ConstraintProvider）的评分器。
 *
 * 这份评分器包含：
 * 1) 资源容量约束（Hard）
 *    - 可再生资源（renewable）：按“每一天”的用量不能超过 capacity（典型：设备/人力/班次产能）。
 *    - 不可再生资源（nonrenewable）：全周期累计用量不能超过 capacity（典型：预算/总量约束）。
 *
 * 2) 库存约束（Hard，路线 C）
 *    - 使用 InventoryEvent（产出/消耗事件）在时间轴上累加库存余额。
 *    - 任意时刻（我们只检查“事件发生日”，因为库存只在事件日跳变）库存不能为负。
 *
 * 3) 目标（Medium/Soft）
 *    - project 迟交（Medium）：SINK 的完工时间超过 criticalPathEndDate 的部分罚分。
 *    - makespan（Soft）：所有 project 的 SINK 最大完工时间越小越好。
 *
 * 多 Project 物料互相影响/库存竞争：
 * - 只要多个 project 的 InventoryEvent 引用的是同一个 Item 实例（同一个对象），
 *   那么它们会自动共享库存并产生竞争/供料关系。
 */
public class ProjectJobSchedulingConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                nonRenewableResourceCapacity(constraintFactory),
                renewableResourceCapacity(constraintFactory),
                inventoryNeverNegative(constraintFactory), // 路线C库存硬约束
                totalProjectDelay(constraintFactory),
                totalMakespan(constraintFactory)
        };
    }

    // ----------------------------------------------------------------
    // Hard：不可再生资源（总量）容量约束
    // ----------------------------------------------------------------
    protected Constraint nonRenewableResourceCapacity(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ResourceRequirement.class)
                // 只处理不可再生资源：例如“总预算/总耗材限额”等（注意：��不是路线C库存）
                .filter(resourceReq -> !resourceReq.isResourceRenewable())
                // 把资源需求和 Allocation（排程结果）按 executionMode 连接起来
                .join(Allocation.class,
                        Joiners.equal(ResourceRequirement::getExecutionMode, Allocation::getExecutionMode))
                // 按资源聚合：把所有使用该资源的 requirement 累计求和
                .groupBy((requirement, allocation) -> requirement.getResource(),
                        ConstraintCollectors.sum((requirement, allocation) -> requirement.getRequirement()))
                // 超过 capacity 的部分算硬约束违规
                .filter((resource, requirements) -> requirements > resource.getCapacity())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (resource, requirements) -> requirements - resource.getCapacity())
                .asConstraint("Non-renewable resource capacity");
    }

    // ----------------------------------------------------------------
    // Hard：可再生资源（按天）容量约束
    // ----------------------------------------------------------------
    protected Constraint renewableResourceCapacity(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ResourceRequirement.class)
                // 只处理可再生资源：例如设备/产线/人力（每天都有固定产能）
                .filter(ResourceRequirement::isResourceRenewable)
                // 连接到 Allocation，得到该 resourceRequirement 在哪个 allocation 上被使用
                .join(Allocation.class,
                        Joiners.equal(ResourceRequirement::getExecutionMode, Allocation::getExecutionMode))
                // 防止 start/end 为空导致 NPE（初始化阶段或后续允许 null 时会出现）
                .filter((resourceReq, allocation) -> allocation.getStartDate() != null && allocation.getEndDate() != null)
                /**
                 * 把一个 allocation 的执行区间 [startDate, endDate) 展开成多个“天”：
                 * 例如 start=2, end=5，则展开为 day=2,3,4。
                 *
                 * 这里使用 flattenLast((resourceReq, allocation) -> days...) 的写法，
                 * 避免因为 raw List 等原因导致 lambda 推断成 Object，进而出现 getRequirement() 找不到的编译错误。
                 */
                .flattenLast((allocation) -> {
                    return IntStream.range(allocation.getStartDate(), allocation.getEndDate())
                            .boxed()
                            .collect(Collectors.toList());
                })
                // 以 (resource, hour) 聚合：求当前小时该资源的总使用量
                .groupBy((ResourceRequirement resourceReq, Integer hour) -> resourceReq.getResource(),
                        (ResourceRequirement resourceReq, Integer hour) -> hour,
                        ConstraintCollectors.sum((ResourceRequirement resourceReq, Integer hour) -> resourceReq.getRequirement()))
                // 当天使用量超过 capacity：硬约束违规
                .filter((resource, day, totalRequirement) -> totalRequirement > resource.getCapacity())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (resource, day, totalRequirement) -> totalRequirement - resource.getCapacity())
                .asConstraint("Renewable resource capacity");
    }

    // ----------------------------------------------------------------
    // Hard：路线 C 库存余额不能为负
    // ----------------------------------------------------------------
    /**
     * 路线 C 的核心：库存余额随时间变化（产出/消耗），并且必须始终 >= 0。
     *
     * 定义：
     * - 每个 InventoryEvent 有 quantity：
     *   + 正数：产出/入库
     *   + 负数：消耗/出库
     * - eventDate 从 allocation 推导：
     *   START -> allocation.startDate
     *   END   -> allocation.endDate
     *
     * 库存余额：
     * balance(item, t) = initialStock(item) + sum(event.quantity where eventDate <= t)
     *
     * 约束：balance(item, t) >= 0
     *
     * 为什么只检查“事件日”？
     * - 因为库存只有在事件发生时才会跳变，在两个事件日之间库存不变。
     * - 因此最小库存一定出现在某个事件日。
     */
    protected Constraint inventoryNeverNegative(ConstraintFactory constraintFactory) {
        // Step 1：遍历所有事件，构造“候选检查日”(item, dayCandidate)。
        // dayCandidate 取每个事件本身的 eventDate。
        return constraintFactory.forEach(InventoryEvent.class)
                .filter(e -> e.getItem() != null)
                .filter(e -> e.getEventDate() != null) // allocation 未排程时 eventDate 可能为 null
                .groupBy(InventoryEvent::getItem,
                        InventoryEvent::getEventDate)
                /**
                 * Step 2：对每个 (item, dayCandidate)，找出同一 item 且 eventDate <= dayCandidate 的所有事件，
                 * 用于计算前缀和（prefix sum）。
                 */
                .join(InventoryEvent.class,
                        Joiners.equal((item, day) -> item, InventoryEvent::getItem),
                        Joiners.lessThanOrEqual((item, day) -> day, InventoryEvent::getEventDate))
                // Step 3：对这些事件的 quantity 求和，得到截至 dayCandidate 的 prefixDelta
                .groupBy((item, day, e2) -> item,
                        (item, day, e2) -> day,
                        ConstraintCollectors.sum((item, day, e2) -> e2.getQuantity()))
                // Step 4：如果 initialStock + prefixDelta < 0，则缺料（shortage）
                .filter((item, day, prefixDelta) -> item.getInitialStock() + prefixDelta < 0)
                // 罚分为缺料数量（正数），hard 分会减去该值
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (item, day, prefixDelta) -> -(item.getInitialStock() + prefixDelta))
                .asConstraint("Inventory never negative");
    }

    // ----------------------------------------------------------------
    // Medium：Project 迟交（只关心 SINK）
    // ----------------------------------------------------------------
    protected Constraint totalProjectDelay(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Allocation.class)
                .filter(allocation -> allocation.getEndDate() != null)
                // 只统计 SINK（项目完工节点）
                .filter(allocation -> allocation.getJobType() == JobType.SINK)
                /**
                 * 迟交 = max(0, endDate - criticalPathEndDate)
                 * - 不迟交时罚 0
                 * - 迟交越多罚越多（Medium）
                 */
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        allocation -> Math.max(0, allocation.getEndDate() - allocation.getProjectCriticalPathEndDate()))
                .asConstraint("Total project delay");
    }

    // ----------------------------------------------------------------
    // Soft：Makespan（只关心 SINK 的最大完工时间）
    // ----------------------------------------------------------------
    protected Constraint totalMakespan(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Allocation.class)
                .filter(allocation -> allocation.getEndDate() != null)
                .filter(allocation -> allocation.getJobType() == JobType.SINK)
                // 取所有 project 的完工最大 endDate
                .groupBy(ConstraintCollectors.max(Allocation::getEndDate))
                // makespan 越大 soft 罚分越多 => 求解器会倾向缩短最大完工时间
                .penalize(HardMediumSoftScore.ONE_SOFT, maxEndDate -> maxEndDate)
                .asConstraint("Total makespan");
    }
}