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
import com.iimsoft.schduler.domain.Item;
import com.iimsoft.schduler.domain.JobType;
import com.iimsoft.schduler.domain.ResourceRequirement;

/**
 * Constraint Streams based scoring (ConstraintProvider).
 *
 * Includes:
 * - Resource capacity constraints (renewable/nonrenewable).
 * - Project delay and makespan objectives.
 * - Route-C inventory constraint: inventory must never go negative.
 *
 * Inventory modeling notes (Route C):
 * - Each InventoryEvent has quantity (+ produce, - consume) and an eventDate derived from its Allocation schedule.
 * - Inventory balance changes only on event dates, so it is sufficient to check balance at each event date.
 * - Multi-project coupling is automatic as long as all projects share the same Item instances.
 */
public class ProjectJobSchedulingConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                nonRenewableResourceCapacity(constraintFactory),
                renewableResourceCapacity(constraintFactory),
                inventoryNeverNegative(constraintFactory), // Route C inventory hard constraint
                totalProjectDelay(constraintFactory),
                totalMakespan(constraintFactory)
        };
    }

    // ----------------------------------------------------------------
    // Hard: nonrenewable resource capacity (total consumption)
    // ----------------------------------------------------------------
    protected Constraint nonRenewableResourceCapacity(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ResourceRequirement.class)
                .filter(resourceReq -> !resourceReq.isResourceRenewable())
                .join(Allocation.class,
                        Joiners.equal(ResourceRequirement::getExecutionMode, Allocation::getExecutionMode))
                .groupBy((requirement, allocation) -> requirement.getResource(),
                        ConstraintCollectors.sum((requirement, allocation) -> requirement.getRequirement()))
                .filter((resource, requirements) -> requirements > resource.getCapacity())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (resource, requirements) -> requirements - resource.getCapacity())
                .asConstraint("Non-renewable resource capacity");
    }

    // ----------------------------------------------------------------
    // Hard: renewable resource capacity per day
    // ----------------------------------------------------------------
    protected Constraint renewableResourceCapacity(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ResourceRequirement.class)
                .filter(ResourceRequirement::isResourceRenewable)
                .join(Allocation.class,
                        Joiners.equal(ResourceRequirement::getExecutionMode, Allocation::getExecutionMode))
                // Guard against null start/end (can happen during initialization or if you later allow nullables)
                .filter((resourceReq, allocation) -> allocation.getStartDate() != null && allocation.getEndDate() != null)
                .flattenLast((resourceReq, allocation) -> IntStream.range(allocation.getStartDate(), allocation.getEndDate())
                        .boxed()
                        .collect(Collectors.toList()))
                .groupBy((resourceReq, date) -> resourceReq.getResource(),
                        (resourceReq, date) -> date,
                        ConstraintCollectors.sum((resourceReq, date) -> resourceReq.getRequirement()))
                .filter((resource, date, totalRequirement) -> totalRequirement > resource.getCapacity())
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (resource, date, totalRequirement) -> totalRequirement - resource.getCapacity())
                .asConstraint("Renewable resource capacity");
    }

    // ----------------------------------------------------------------
    // Hard: Route-C inventory never negative
    // ----------------------------------------------------------------

    /**
     * For each Item and each event day t (inventory changes only on event days):
     *
     * balance(item, t) = initialStock(item) + sum(event.quantity where eventDate <= t)
     *
     * Must satisfy balance(item, t) >= 0.
     *
     * Penalization = shortage amount at that day:
     * shortage = -(balance) if balance < 0 else 0
     */
    protected Constraint inventoryNeverNegative(ConstraintFactory constraintFactory) {
        // Step 1: iterate over event-days (item, dayCandidate).
        // We choose dayCandidate to be every event date of this item.
        return constraintFactory.forEach(InventoryEvent.class)
                .filter(e -> e.getItem() != null)
                .filter(e -> e.getEventDate() != null) // active event only
                .groupBy(InventoryEvent::getItem,
                        InventoryEvent::getEventDate)
                // Step 2: for each (item, dayCandidate), join all events of that item with date <= dayCandidate
                .join(InventoryEvent.class,
                        Joiners.equal((item, day) -> item, InventoryEvent::getItem),
                        Joiners.lessThanOrEqual((item, day) -> day, InventoryEvent::getEventDate))
                // Step 3: compute prefix sum delta up to dayCandidate
                .groupBy((item, day, e2) -> item,
                        (item, day, e2) -> day,
                        ConstraintCollectors.sum((item, day, e2) -> e2.getQuantity()))
                // Step 4: check balance and penalize shortage
                .filter((item, day, prefixDelta) -> item.getInitialStock() + prefixDelta < 0)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (item, day, prefixDelta) -> -(item.getInitialStock() + prefixDelta))
                .asConstraint("Inventory never negative");
    }

    // ----------------------------------------------------------------
    // Medium: total project delay
    // ----------------------------------------------------------------
    protected Constraint totalProjectDelay(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Allocation.class)
                .filter(allocation -> allocation.getEndDate() != null)
                .filter(allocation -> allocation.getJobType() == JobType.SINK)
                // Delay = max(0, end - criticalPathEnd)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        allocation -> Math.max(0, allocation.getEndDate() - allocation.getProjectCriticalPathEndDate()))
                .asConstraint("Total project delay");
    }

    // ----------------------------------------------------------------
    // Soft: makespan
    // ----------------------------------------------------------------
    protected Constraint totalMakespan(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Allocation.class)
                .filter(allocation -> allocation.getEndDate() != null)
                .filter(allocation -> allocation.getJobType() == JobType.SINK)
                .groupBy(ConstraintCollectors.max(Allocation::getEndDate))
                .penalize(HardMediumSoftScore.ONE_SOFT, maxEndDate -> maxEndDate)
                .asConstraint("Total makespan");
    }

    // Optional: if you want a constraint that ensures Items exist in a global list, you can join Schedule facts.
    // Not necessary for correctness, as InventoryEvent already references Item.
}