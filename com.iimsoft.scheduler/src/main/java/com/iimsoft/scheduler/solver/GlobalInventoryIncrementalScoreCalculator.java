package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

import java.util.*;

/**
 * 增量计分器（优化版）：
 * - 硬分：
 *   1) 全局库存（所有物料、所有时间槽）不得为负（用在手曲线 + 增量线性更新）。
 *   2) 产线必须支持所选工艺（新增）
 * - 软分：
 *   1) 分桶净额：比例达成奖励（封顶）/完全满足奖励/未满足惩罚/超产惩罚（>=3%）。
 *   2) 库存持有成本：对在手量超安全库存的部分，按“件×时槽”计小额扣分（抑制提前占库）。
 *   3) 工艺切换惩罚：同一条线相邻两个时槽，router不同则扣分（减少频繁切换）。
 *
 * 性能优化：
 * - reset 时的“同槽子件消耗”由 child→arcs 直接映射计算，避免“对每个子件遍历所有父件”。
 * - 预构建 line×slot 的邻接索引，用于切换成本在变量变更时 O(1) 更新两对相邻格子。
 */
public class GlobalInventoryIncrementalScoreCalculator implements IncrementalScoreCalculator<ProductionSchedule, HardSoftScore> {

    // 目标权重（可参数化）
    private static final int PROP_REWARD_WEIGHT      = 20;     // 比例达成（每桶基准1000分×权重）
    private static final int COMPLETE_REWARD_BONUS   = 5000;   // 桶完全满足且不超容忍度时一次性奖励（提高奖励鼓励精确满足）
    private static final int UNMET_PENALTY_WEIGHT    = 3000;   // 未满足（件）惩罚，乘以优先级（提高未满足惩罚）
    private static final int OVER_PENALTY_WEIGHT     = 5000;   // 超产（超过容忍阈值部分/件）惩罚，乘以优先级（大幅提高超产惩罚）

    private static final int HOLDING_COST_WEIGHT     = 1;      // 库存持有成本：超安全库存的件×时槽×权重（降低以避免干扰成品目标）
    private static final int SAFETY_SHORTAGE_WEIGHT  = 10;     // 低于安全库存的惩罚：(安全库存-实际库存)×时槽×权重
    private static final int CHANGEOVER_PENALTY      = 100;    // 相邻时槽工艺切换惩罚（同线）（大幅降低以避免干扰成品满足）
    private static final int BATCH_PRODUCTION_REWARD = 50;     // 连续生产同一工艺的奖励：仅对部件生产（每对相邻时槽）
    private static final int HARD_UNSUPPORTED_WEIGHT = 100_000; // 产线不支持工艺的硬惩罚（降低避免溢出）
    private static final int NIGHT_SHIFT_PENALTY     = 10;     // 夜班额外成本惩罚（每件）（大幅降低）
    private static final int HARD_PREDECESSOR_VIOLATION = 100_000; // 工序依赖违反的硬惩罚（降低避免溢出）
    private static final int HARD_UNMET_DEMAND_WEIGHT = 10_000;    // 未满足需求的硬约束权重（降低避免溢出）
    private static final int HARD_BOM_SHORTAGE_WEIGHT = 100_000;    // BOM消耗不足的硬约束权重（避免溢出，仍高于其他约束）
    private static final int FINISHED_TARDINESS_WEIGHT = 10_000; // 成品迟期每槽惩罚（恢复原权重）
    private static final int COMPONENT_TARDINESS_WEIGHT = 2_000; // 部件迟期每槽惩罚（恢复原权重）
    
    // 参数化配置（可在运行时修改）
    private int finishedProductTolerancePercent = 0;  // 成品超产容忍度（0.1%）
    private int componentTolerancePercent = 3;          // 部件超产容忍度（3%，为不同需求提供缓冲）

    // 工作数据
    private ProductionSchedule solution;

    private List<TimeSlot> slots; // index 升序
    private int slotCount;

    // 初始在手 & 安全库存
    private Map<Item, Integer> initialOnHand = new HashMap<>();
    private Map<Item, Integer> safetyStockMap = new HashMap<>();

    // 每物料每槽的“在手库存”（槽结束时）
    private Map<Item, int[]> onHandPerSlot = new HashMap<>();
    // 每物料每槽的“自身产量”（不含BOM消耗）
    private Map<Item, int[]> producedPerSlot = new HashMap<>();

    // BOM 映射
    private Map<Item, List<BomArc>> parentToArcs = new HashMap<>(); // 父 -> 子列表
    private Map<Item, List<BomArc>> childToArcs  = new HashMap<>(); // 子 -> 父列表

    // 需求分桶（按 item、到期 index 升序）
    private Map<Item, List<DemandBucket>> demandsByItem = new HashMap<>();

    // 邻接索引：每条线的（slotIndex -> assignment）数组，用于切换成本增量更新
    private Map<ProductionLine, ProductionAssignment[]> lineSlotIndex = new LinkedHashMap<>();

    // 分数组件
    private int hardPenalty;       // 库存缺口累加（>0），最终取负作为硬分
    private int softBucketScore;   // 分桶净额（奖励-惩罚）
    private int holdingPenalty;    // 库存持有成本累加
    private int safetyShortage;    // 安全库存短缺惩罚累加
    private int changeoverPenalty; // 工艺切换惩罚累加
    private int batchProductionReward; // 批量生产奖励累加（连续相同工艺）
    private int hardUnsupportedPenalty; // 产线不支持工艺的硬惩罚累计（>0）
    private int nightShiftCost;    // 夜班额外成本累计
    private int predecessorViolations; // 工序依赖违反次数累计（>0）
    private int finishedUnmetDemand;   // 未满足成品需求数累计（硬约束）（>0）
    private int componentUnmetDemand;  // 未满足部件需求数累计（硬约束）（>0）
    private int hardBomShortage;   // BOM消耗超产量硬约束（>0）
    private int orderTardinessPenalty; // 订单级迟期惩罚累计（>0）
    private int orderBomShortagePenalty; // 订单级BOM短缺硬惩罚（>0）
    
    // 订单级BOM追踪
    private List<OrderBomTracking> orderBomTrackings = new ArrayList<>();
    
    // 公开setter方法，允许运行时配置
    public void setFinishedProductTolerancePercent(int percent) {
        this.finishedProductTolerancePercent = Math.max(0, Math.min(100, percent));
    }
    
    public void setComponentTolerancePercent(int percent) {
        this.componentTolerancePercent = Math.max(0, Math.min(100, percent));
    }

    @Override
    public void resetWorkingSolution(ProductionSchedule solution) {
        this.solution = solution;

        // 槽序
        this.slots = new ArrayList<>(solution.getTimeSlotList());
        this.slots.sort(Comparator.comparingInt(TimeSlot::getIndex));
        this.slotCount = slots.size();

        // 初始库存与安全库存
        initialOnHand.clear();
        safetyStockMap.clear();
        for (ItemInventory inv : solution.getInventoryList()) {
            initialOnHand.put(inv.getItem(), inv.getInitialOnHand());
            safetyStockMap.put(inv.getItem(), inv.getSafetyStock());
        }

        // BOM 映射
        parentToArcs.clear();
        childToArcs.clear();
        for (BomArc arc : solution.getBomArcList()) {
            parentToArcs.computeIfAbsent(arc.getParent(), k -> new ArrayList<>()).add(arc);
            childToArcs.computeIfAbsent(arc.getChild(),  k -> new ArrayList<>()).add(arc);
        }

        // 初始化 per-item 数组
        Set<Item> allItems = collectAllItems(solution);
        producedPerSlot.clear();
        onHandPerSlot.clear();
        for (Item it : allItems) {
            producedPerSlot.put(it, new int[slotCount]);
            onHandPerSlot.put(it, new int[slotCount]);
        }

        // 填充每槽产量
        for (ProductionAssignment a : solution.getAssignmentList()) {
            Router r = a.getRouter();
            if (r == null) continue;
            int s = a.getTimeSlot().getIndex();
            producedPerSlot.get(r.getItem())[s] += r.getSpeedPerHour();
        }

        // 计算在手曲线 + 硬分 + 库存持有成本 + 安全库存短缺惩罚
        hardPenalty = 0;
        holdingPenalty = 0;
        safetyShortage = 0;
        for (Item it : allItems) {
            int[] onHands = onHandPerSlot.get(it);
            int cur = initialOnHand.getOrDefault(it, 0);
            int safety = safetyStockMap.getOrDefault(it, 0);

            for (int s = 0; s < slotCount; s++) {
                // 修复：先消耗子件，再入库父件（符合实际生产逻辑）
                // 消耗：本槽由父件产出导致该物料（若为子件）被消耗（child -> arcs）
                int consumed = 0;
                for (BomArc arc : childToArcs.getOrDefault(it, Collections.emptyList())) {
                    int parentQty = producedPerSlot.get(arc.getParent())[s];
                    if (parentQty != 0) {
                        consumed += parentQty * arc.getQuantityPerParent();
                    }
                }
                cur -= consumed;
                // 入库：本物料本槽产量
                cur += producedPerSlot.get(it)[s];

                onHands[s] = cur;

                // 硬分：缺口累加（负库存）
                if (cur < 0) {
                    hardPenalty += -cur;
                }
                
                // 软分：库存相关成本
                if (cur >= 0) {
                    // 低于安全库存的惩罚（cur < safety）
                    int shortage = Math.max(0, safety - cur);
                    if (shortage > 0) {
                        safetyShortage += shortage * SAFETY_SHORTAGE_WEIGHT;
                    }
                    // 库存持有成本：超过安全库存的部分（cur > safety）
                    int over = Math.max(0, cur - safety);
                    if (over > 0) {
                        holdingPenalty += over * HOLDING_COST_WEIGHT;
                    }
                }
            }
        }

        // 邻接索引 & 初始切换惩罚/批量奖励
        buildLineSlotIndex();
        changeoverPenalty = 0;
        batchProductionReward = 0;
        for (Map.Entry<ProductionLine, ProductionAssignment[]> e : lineSlotIndex.entrySet()) {
            ProductionAssignment[] arr = e.getValue();
            for (int s = 0; s + 1 < slotCount; s++) {
                changeoverPenalty += pairContribution(arr[s], arr[s + 1]);
                batchProductionReward += batchReward(arr[s], arr[s + 1]);
            }
        }

        // 构建需求桶并计算软分 + 订单迟期惩罚
        buildDemandBuckets();
        softBucketScore = 0;
        orderTardinessPenalty = 0;
        for (List<DemandBucket> buckets : demandsByItem.values()) {
            for (DemandBucket db : buckets) {
                softBucketScore += db.recomputeContributionWithDifferentTolerances(finishedProductTolerancePercent, componentTolerancePercent);
                db.cachedTardinessPenalty = computeTardinessPenalty(db);
                orderTardinessPenalty += db.cachedTardinessPenalty;
            }
        }

        // 产线能力硬约束：初始化不支持工艺的数量
        hardUnsupportedPenalty = 0;
        for (ProductionAssignment a : solution.getAssignmentList()) {
            Router r = a.getRouter();
            if (r != null && !a.getLine().supports(r)) {
                hardUnsupportedPenalty += HARD_UNSUPPORTED_WEIGHT;
            }
        }
        
        // 夜班成本：初始化夜班生产的额外成本
        nightShiftCost = 0;
        for (ProductionAssignment a : solution.getAssignmentList()) {
            Router r = a.getRouter();
            if (r != null && a.getTimeSlot().getShift() == Shift.NIGHT) {
                nightShiftCost += r.getSpeedPerHour() * NIGHT_SHIFT_PENALTY;
            }
        }
        
        // 工序依赖约束：检查前置工序完成时间
        predecessorViolations = 0;
        predecessorViolations = calculatePredecessorViolations(solution);
        
        // 需求满足硬约束：计算未满足需求数（按物料类型区分）
        finishedUnmetDemand = 0;
        componentUnmetDemand = 0;
        for (List<DemandBucket> buckets : demandsByItem.values()) {
            for (DemandBucket db : buckets) {
                int available = Math.max(0, db.producedCumAtDue - db.prevDemandSum);
                int unmet = Math.max(0, db.demand - available);
                if (unmet > 0) {
                    if (db.isFinishedProductBucket()) {
                        finishedUnmetDemand += unmet;
                    } else {
                        componentUnmetDemand += unmet;
                    }
                }
            }
        }
        
        // BOM消耗硬约束：对每个子件，检查消耗量是否超过生产量+初始库存
        hardBomShortage = 0;
        for (Item child : childToArcs.keySet()) {
            int totalConsumed = 0;
            for (int s = 0; s < slotCount; s++) {
                for (BomArc arc : childToArcs.get(child)) {
                    totalConsumed += producedPerSlot.get(arc.getParent())[s] * arc.getQuantityPerParent();
                }
            }
            int totalProduced = 0;
            for (int s = 0; s < slotCount; s++) {
                totalProduced += producedPerSlot.get(child)[s];
            }
            int initialStock = initialOnHand.getOrDefault(child, 0);
            int shortage = Math.max(0, totalConsumed - totalProduced - initialStock);
            hardBomShortage += shortage;
        }
        
        // 订单级BOM追踪：为每个成品订单构建BOM需求
        buildOrderBomTrackings();
        // 注意：初始不分配部件，由增量更新维护
        orderBomShortagePenalty = 0;
    }

    @Override public void beforeEntityAdded(Object entity) { }
    @Override public void afterEntityAdded(Object entity) { }

    @Override
    public void beforeVariableChanged(Object entity, String variableName) {
        if (!(entity instanceof ProductionAssignment) || !"router".equals(variableName)) return;
        ProductionAssignment a = (ProductionAssignment) entity;
        Router old = a.getRouter();
        if (old != null) {
            // 产线能力硬分调整（移除旧router）
            if (!a.getLine().supports(old)) {
                hardUnsupportedPenalty -= HARD_UNSUPPORTED_WEIGHT;
            }
            // 夜班成本调整（移除旧router）
            if (a.getTimeSlot().getShift() == Shift.NIGHT) {
                nightShiftCost -= old.getSpeedPerHour() * NIGHT_SHIFT_PENALTY;
            }
            // 工序依赖调整（移除旧router）
            predecessorViolations -= countPredecessorViolationsForAssignment(a, old);
            // 先移除相邻对（旧router）的切换贡献
            adjustNeighborPairs(a, -1); // -1 表示移除旧贡献
            // 产量与库存线性增量（移除旧产量）
            applyAssignmentDelta(a.getTimeSlot().getIndex(), old.getItem(), -old.getSpeedPerHour());
        }
    }

    @Override
    public void afterVariableChanged(Object entity, String variableName) {
        if (!(entity instanceof ProductionAssignment) || !"router".equals(variableName)) return;
        ProductionAssignment a = (ProductionAssignment) entity;
        Router now = a.getRouter();
        if (now != null) {
            // 产线能力硬分调整（加入新router）
            if (!a.getLine().supports(now)) {
                hardUnsupportedPenalty += HARD_UNSUPPORTED_WEIGHT;
            }
            // 夜班成本调整（加入新router）
            if (a.getTimeSlot().getShift() == Shift.NIGHT) {
                nightShiftCost += now.getSpeedPerHour() * NIGHT_SHIFT_PENALTY;
            }
            // 工序依赖调整（加入新router）
            predecessorViolations += countPredecessorViolationsForAssignment(a, now);
            // 产量与库存线性增量（加入新产量）
            applyAssignmentDelta(a.getTimeSlot().getIndex(), now.getItem(), +now.getSpeedPerHour());
            // 重新加入相邻对（新router）的切换贡献
            adjustNeighborPairs(a, +1); // +1 表示加入新贡献
        }
    }

    @Override
    public void beforeEntityRemoved(Object entity) {
        if (!(entity instanceof ProductionAssignment)) return;
        ProductionAssignment a = (ProductionAssignment) entity;
        Router r = a.getRouter();
        if (r != null) {
            if (!a.getLine().supports(r)) {
                hardUnsupportedPenalty -= HARD_UNSUPPORTED_WEIGHT;
            }
            if (a.getTimeSlot().getShift() == Shift.NIGHT) {
                nightShiftCost -= r.getSpeedPerHour() * NIGHT_SHIFT_PENALTY;
            }
            predecessorViolations -= countPredecessorViolationsForAssignment(a, r);
            adjustNeighborPairs(a, -1);
            applyAssignmentDelta(a.getTimeSlot().getIndex(), r.getItem(), -r.getSpeedPerHour());
        }
    }

    @Override public void afterEntityRemoved(Object entity) { }

    @Override
    public HardSoftScore calculateScore() {
        // 重新计算BOM消耗硬约束（因为增量更新复杂）
        hardBomShortage = 0;
        for (Item child : childToArcs.keySet()) {
            int totalConsumed = 0;
            for (int s = 0; s < slotCount; s++) {
                for (BomArc arc : childToArcs.get(child)) {
                    totalConsumed += producedPerSlot.get(arc.getParent())[s] * arc.getQuantityPerParent();
                }
            }
            int totalProduced = 0;
            for (int s = 0; s < slotCount; s++) {
                totalProduced += producedPerSlot.get(child)[s];
            }
            int initialStock = initialOnHand.getOrDefault(child, 0);
            int shortage = Math.max(0, totalConsumed - totalProduced - initialStock);
            hardBomShortage += shortage;
        }
        
        // 重新计算订单级BOM短缺惩罚（避免溢出，保持合理权重）
        orderBomShortagePenalty = 0;
        for (OrderBomTracking obt : orderBomTrackings) {
            int shortage = obt.calculateComponentShortage();
            orderBomShortagePenalty += shortage * 10_000;  // 降低到10K避免溢出，仍引导部件生产
        }
        
        int hard = -hardPenalty - hardUnsupportedPenalty - (predecessorViolations * HARD_PREDECESSOR_VIOLATION) 
                 - (finishedUnmetDemand * 100_000) - (componentUnmetDemand * 10_000) 
                 - (hardBomShortage * HARD_BOM_SHORTAGE_WEIGHT) - orderBomShortagePenalty;
        int soft = softBucketScore - holdingPenalty - safetyShortage - changeoverPenalty + batchProductionReward - nightShiftCost - orderTardinessPenalty;
        return HardSoftScore.of(hard, soft);
    }

    // ----------------- 增量更新核心 -----------------

    private void applyAssignmentDelta(int slotIndex, Item item, int qtyDelta) {
        if (qtyDelta == 0) return;

        // 1) 更新该槽产量
        producedPerSlot.get(item)[slotIndex] += qtyDelta;

        // 2) 该物料在手曲线：自 slotIndex 起 +qtyDelta（硬分 + 持有成本 增量）
        applyOnHandLinearDelta(item, slotIndex, qtyDelta);

        // 3) 子件在手曲线：对每个 child，自 slotIndex 起 -(qtyDelta * arcQty)
        for (BomArc arc : parentToArcs.getOrDefault(item, Collections.emptyList())) {
            Item child = arc.getChild();
            int childDelta = -qtyDelta * arc.getQuantityPerParent();
            if (childDelta != 0) {
                applyOnHandLinearDelta(child, slotIndex, childDelta);
            }
        }

        // 4) 分桶软分：仅该物料、且到期 index ≥ slotIndex 的桶受影响
        List<DemandBucket> buckets = demandsByItem.getOrDefault(item, Collections.emptyList());
        for (DemandBucket db : buckets) {
            if (db.dueIndex >= slotIndex) {
                // 更新硬约束：计算未满足数变化
                int prevAvailable = Math.max(0, db.producedCumAtDue - db.prevDemandSum);
                int prevUnmet = Math.max(0, db.demand - prevAvailable);
                
                softBucketScore -= db.cachedContribution;
                db.producedCumAtDue += qtyDelta;
                
                int newAvailable = Math.max(0, db.producedCumAtDue - db.prevDemandSum);
                int newUnmet = Math.max(0, db.demand - newAvailable);
                int unmetChange = newUnmet - prevUnmet;
                
                // 更新按物料类型区分的未满足需求硬约束
                if (db.isFinishedProductBucket()) {
                    finishedUnmetDemand += unmetChange;
                } else {
                    componentUnmetDemand += unmetChange;
                }
                
                softBucketScore += db.recomputeContributionWithDifferentTolerances(finishedProductTolerancePercent, componentTolerancePercent);

                // 订单迟期惩罚增量更新：先移除旧值，再按最新产量重算
                orderTardinessPenalty -= db.cachedTardinessPenalty;
                db.cachedTardinessPenalty = computeTardinessPenalty(db);
                orderTardinessPenalty += db.cachedTardinessPenalty;
            }
        }
        
        // 5) 订单级BOM追踪：如果该物料是部件，更新所有相关成品订单的部件分配
        if (item.getItemType() != com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT) {
            for (OrderBomTracking obt : orderBomTrackings) {
                if (obt.requiredComponentQty.containsKey(item)) {
                    obt.allocateComponent(item, qtyDelta);
                }
            }
        }
    }

    // 自 fromSlot 起，对 onHand[item][s] 统一 +delta，并精确调整"硬分缺口 + 持有成本 + 安全库存短缺"
    private void applyOnHandLinearDelta(Item item, int fromSlot, int delta) {
        int[] onHands = onHandPerSlot.get(item);
        int safety = safetyStockMap.getOrDefault(item, 0);

        for (int s = fromSlot; s < slotCount; s++) {
            int oldVal = onHands[s];
            int newVal = oldVal + delta;

            // 硬分：缺口变化（负库存）
            int oldDef = Math.max(0, -oldVal);
            int newDef = Math.max(0, -newVal);
            hardPenalty += (newDef - oldDef);

            // 软分：库存相关成本变化（仅当库存非负时）
            if (oldVal >= 0) {
                // 旧值的安全库存短缺
                int oldShortage = Math.max(0, safety - oldVal);
                // 旧值的持有成本
                int oldOver = Math.max(0, oldVal - safety);
                holdingPenalty -= oldOver * HOLDING_COST_WEIGHT;
                safetyShortage -= oldShortage * SAFETY_SHORTAGE_WEIGHT;
            }
            
            if (newVal >= 0) {
                // 新值的安全库存短缺
                int newShortage = Math.max(0, safety - newVal);
                // 新值的持有成本
                int newOver = Math.max(0, newVal - safety);
                holdingPenalty += newOver * HOLDING_COST_WEIGHT;
                safetyShortage += newShortage * SAFETY_SHORTAGE_WEIGHT;
            }

            onHands[s] = newVal;
        }
    }

    // ----------------- 切换惩罚（相邻对） -----------------

    private void buildLineSlotIndex() {
        lineSlotIndex.clear();
        // 初始化每条线的数组
        for (ProductionLine line : solution.getLineList()) {
            lineSlotIndex.put(line, new ProductionAssignment[slotCount]);
        }
        // 放入 assignment
        for (ProductionAssignment a : solution.getAssignmentList()) {
            ProductionLine line = a.getLine();
            int idx = a.getTimeSlot().getIndex();
            ProductionAssignment[] arr = lineSlotIndex.get(line);
            if (arr != null && idx >= 0 && idx < slotCount) {
                arr[idx] = a;
            }
        }
    }

    // 根据当前 a 的位置，更新两对相邻对 (s-1,s) 与 (s,s+1) 的切换惩罚和批量奖励，sign = +1 添加，-1 移除
    private void adjustNeighborPairs(ProductionAssignment a, int sign) {
        ProductionAssignment[] arr = lineSlotIndex.get(a.getLine());
        if (arr == null) return;
        int s = a.getTimeSlot().getIndex();

        if (s - 1 >= 0) {
            changeoverPenalty += sign * pairContribution(arr[s - 1], arr[s]);
            batchProductionReward += sign * batchReward(arr[s - 1], arr[s]);
        }
        if (s + 1 < slotCount) {
            changeoverPenalty += sign * pairContribution(arr[s], arr[s + 1]);
            batchProductionReward += sign * batchReward(arr[s], arr[s + 1]);
        }
    }

    // 相邻对的贡献：router 不同（且都非空）则罚，否则 0
    private int pairContribution(ProductionAssignment a1, ProductionAssignment a2) {
        if (a1 == null || a2 == null) return 0;
        Router r1 = a1.getRouter();
        Router r2 = a2.getRouter();
        if (r1 == null || r2 == null) return 0;
        return (!Objects.equals(r1, r2)) ? CHANGEOVER_PENALTY : 0;
    }
    
    // 批量生产奖励：相同工艺连续生产给予奖励（仅对部件生产，不对成品组装给予）
    private int batchReward(ProductionAssignment a1, ProductionAssignment a2) {
        if (a1 == null || a2 == null) return 0;
        Router r1 = a1.getRouter();
        Router r2 = a2.getRouter();
        if (r1 == null || r2 == null) return 0;
        
        // 只有两个都是部件生产时才给奖励（既不是成品也不是主要的原始投入物料）
        boolean bothAreComponents = 
            r1.getItem().getItemType() != com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT &&
            r2.getItem().getItemType() != com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT;
        
        return (Objects.equals(r1, r2) && bothAreComponents) ? 
            BATCH_PRODUCTION_REWARD : 0;
    }

    // ----------------- 分桶缓存 -----------------

    private void buildDemandBuckets() {
        demandsByItem.clear();

        Map<Item, List<DemandOrder>> grouped = new HashMap<>();
        for (DemandOrder d : solution.getDemandList()) {
            grouped.computeIfAbsent(d.getItem(), k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<Item, List<DemandOrder>> e : grouped.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(DemandOrder::getDueTimeSlotIndex));
        }

        for (Map.Entry<Item, List<DemandOrder>> e : grouped.entrySet()) {
            Item item = e.getKey();
            List<DemandOrder> ds = e.getValue();

            List<DemandBucket> buckets = new ArrayList<>();
            int prevSum = 0;
            for (DemandOrder d : ds) {
                int dueIdx = d.getDueTimeSlotIndex();
                int producedCum = sumProducedUpTo(item, dueIdx);
                DemandBucket db = new DemandBucket(item, d.getQuantity(), dueIdx, prevSum, producedCum, d.getPriority());
                
                // 计算该需求的时间窗口（考虑工序依赖和交期约束）
                calculateTimeWindow(db);
                
                db.recomputeContributionWithDifferentTolerances(finishedProductTolerancePercent, componentTolerancePercent);
                buckets.add(db);
                prevSum += d.getQuantity();
            }
            demandsByItem.put(item, buckets);
        }
    }
    
    /**
     * 计算需求桶的时间窗口：基于工序依赖关系和物料的交期约束
     */
    private void calculateTimeWindow(DemandBucket db) {
        // 最早开始时间：该物料所有前置工序的最晚完成时间
        int earliest = 0;
        
        // 如果该物料有前置物料（BOM树的上游），则需要从它们的交期往后推
        List<BomArc> incomingArcs = childToArcs.getOrDefault(db.item, Collections.emptyList());
        for (BomArc arc : incomingArcs) {
            Item parent = arc.getParent();
            // 父物料的交期推迟一定时间（模拟加工、装配时间）
            // 这里简化为：最早开始 = max(所有父物料的交期)
            int parentDueIdx = 0;
            for (DemandOrder d : solution.getDemandList()) {
                if (d.getItem().equals(parent)) {
                    parentDueIdx = Math.max(parentDueIdx, d.getDueTimeSlotIndex());
                }
            }
            earliest = Math.max(earliest, parentDueIdx);
        }
        
        db.setTimeWindow(earliest, db.dueIndex);
    }

    private int sumProducedUpTo(Item item, int dueIndex) {
        int[] arr = producedPerSlot.get(item);
        int end = Math.min(dueIndex, arr.length - 1);
        int sum = 0;
        for (int i = 0; i <= end; i++) sum += arr[i];
        return sum;
    }

    // --------- 订单级BOM追踪 ---------
    
    /**
     * 订单级别的BOM约束追踪：为每个成品订单维护所需部件的数量和分配状态
     */
    private static class OrderBomTracking {
        final DemandOrder finishedOrder;  // 成品订单
        final Map<Item, Integer> requiredComponentQty = new HashMap<>();  // 该订单需要的各部件数量
        final Map<Item, Integer> allocatedComponentQty = new HashMap<>(); // 已分配给该订单的各部件数量

        OrderBomTracking(DemandOrder finishedOrder) {
            this.finishedOrder = finishedOrder;
        }

        /**
         * 添加该订单对某部件的需求
         */
        void addComponentRequirement(Item component, int qty) {
            requiredComponentQty.merge(component, qty, Integer::sum);
            allocatedComponentQty.putIfAbsent(component, 0);
        }

        /**
         * 分配部件给该订单
         */
        void allocateComponent(Item component, int qty) {
            allocatedComponentQty.merge(component, qty, Integer::sum);
        }

        /**
         * 计算该订单的部件短缺数
         */
        int calculateComponentShortage() {
            int shortage = 0;
            for (Map.Entry<Item, Integer> entry : requiredComponentQty.entrySet()) {
                Item component = entry.getKey();
                int required = entry.getValue();
                int allocated = allocatedComponentQty.getOrDefault(component, 0);
                shortage += Math.max(0, required - allocated);
            }
            return shortage;
        }
    }

    /**
     * 构建订单级BOM追踪：为每个成品订单计算其所需的所有部件
     */
    private void buildOrderBomTrackings() {
        orderBomTrackings.clear();
        
        // 遍历所有成品订单
        for (DemandOrder demand : solution.getDemandList()) {
            if (demand.getItem().getItemType() != com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT) {
                continue;  // 跳过非成品订单
            }
            
            OrderBomTracking obt = new OrderBomTracking(demand);
            
            // 使用BFS/DFS遍历该成品的BOM树，计算所需的所有部件
            Set<Item> visited = new HashSet<>();
            Queue<Item> queue = new LinkedList<>();
            Queue<Integer> qtyQueue = new LinkedList<>();
            
            queue.offer(demand.getItem());
            qtyQueue.offer(demand.getQuantity());
            
            while (!queue.isEmpty()) {
                Item current = queue.poll();
                int currentQty = qtyQueue.poll();
                
                if (visited.contains(current)) {
                    continue;
                }
                visited.add(current);
                
                // 获取该物料作为父件的所有BOM弧（即该物料所需的子件）
                List<BomArc> arcs = parentToArcs.getOrDefault(current, Collections.emptyList());
                for (BomArc arc : arcs) {
                    Item child = arc.getChild();
                    int requiredQty = currentQty * arc.getQuantityPerParent();
                    
                    if (child.getItemType() != com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT) {
                        // 非成品的部件才需要被分配
                        obt.addComponentRequirement(child, requiredQty);
                    }
                    
                    // 继续递归
                    queue.offer(child);
                    qtyQueue.offer(requiredQty);
                }
            }
            
            // 不再在初始化时分配部件，由增量更新维护
            
            orderBomTrackings.add(obt);
        }
    }

    private Set<Item> collectAllItems(ProductionSchedule solution) {

        LinkedHashSet<Item> set = new LinkedHashSet<>();
        for (ItemInventory inv : solution.getInventoryList()) set.add(inv.getItem());
        for (Router r : solution.getRouterList()) set.add(r.getItem());
        for (BomArc arc : solution.getBomArcList()) { set.add(arc.getParent()); set.add(arc.getChild()); }
        for (DemandOrder d : solution.getDemandList()) set.add(d.getItem());
        return set;
    }

    // ----------------- 分桶结构（含缓存） -----------------
    // ----------------- 分桶结构（含缓存） -----------------

    private static class DemandBucket {
        final Item item;
        final int demand;
        final int dueIndex;
        final int prevDemandSum;    // 之前桶需求和（净额）
        final int priority;         // 优先级（1-10，默认5）
        int producedCumAtDue;       // 截止到期时的"自身累计产量"
        int cachedContribution;     // 该桶当前对软分的贡献
        int cachedTardinessPenalty; // 该桶当前的迟期惩罚缓存
        int earliestStartSlot;      // 最早开始时间槽（基于前置工序依赖）
        int latestFinishSlot;       // 最晚完成时间槽（到期时间，考虑时间窗口）

        DemandBucket(Item item, int demand, int dueIndex, int prevDemandSum, int producedCumAtDue, int priority) {
            this.item = item;
            this.demand = demand;
            this.dueIndex = dueIndex;
            this.prevDemandSum = prevDemandSum;
            this.producedCumAtDue = producedCumAtDue;
            this.priority = priority > 0 ? priority : 5; // 默认优先级为5
            this.cachedTardinessPenalty = 0;
            this.earliestStartSlot = 0;  // 默认从第一个时间槽开始
            this.latestFinishSlot = dueIndex;  // 默认截止时间是到期时间
        }
        
        /**
         * 设置时间窗口约束
         */
        void setTimeWindow(int earliest, int latest) {
            this.earliestStartSlot = earliest;
            this.latestFinishSlot = latest;
        }
        
        /**
         * 判断该需求桶是否为成品需求
         */
        boolean isFinishedProductBucket() {
            return item.getItemType() == com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT;
        }

        /**
         * 使用传入的容忍度参数重新计算贡献（保持向后兼容）
         */
        int recomputeContribution(int overTolerancePercent) {
            // 可用量（净额）：自身累计产量 - 之前桶需求和
            int available = Math.max(0, producedCumAtDue - prevDemandSum);

            // 优先级权重：指数曲线，使高优先级影响更显著
            // priority=1: 0.5, priority=5: 1.6, priority=10: 3.0
            double priorityWeight = 0.5 + (priority - 1) * 0.28;
            
            // 成品类型倍数：成品获得极高倍数奖励，强力引导优化器优先完成最终产品
            double itemTypeMultiplier = 1.0;
            boolean isFinishedProduct = (item.getItemType() == com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT);
            if (isFinishedProduct) {
                itemTypeMultiplier = 100_000.0;  // 从3,000提高到100,000
            }

            // 比例奖励（封顶），乘以优先级权重和成品倍数
            int propReward = demand <= 0 ? 0 : (int)(((Math.min(available, demand) * 1000) / demand) * PROP_REWARD_WEIGHT * priorityWeight * itemTypeMultiplier);

            // 完全满足奖励（不超容忍度），乘以优先级权重和成品倍数
            int maxAcceptable = (int) Math.ceil(demand * (1.0 + overTolerancePercent / 100.0));
            int completeReward = (available >= demand && available <= maxAcceptable) ? (int)(COMPLETE_REWARD_BONUS * priorityWeight * itemTypeMultiplier) : 0;

            // 成品额外完整满足奖励：如果成品完全按时满足（在到期前），给予额外大额奖励
            int finishedProductOnTimeBonus = 0;
            if (isFinishedProduct && available >= demand && available <= maxAcceptable) {
                finishedProductOnTimeBonus = 10_000_000;  // 成品按时完成额外奖励
            }

            // 未满足惩罚，乘以优先级权重和成品倍数（成品未满足惩罚更重）
            int unmet = Math.max(0, demand - available);
            int unmetPenalty = (int)(unmet * UNMET_PENALTY_WEIGHT * priorityWeight * itemTypeMultiplier);

            // 超产惩罚（超过容忍阈值部分），乘以优先级权重
            // 成品超产：极重惩罚（100,000倍）；部件超产：3倍惩罚，充分允许为成品组装而必要的部件超产
            int over = Math.max(0, available - demand);
            int tolerated = Math.max(0, (int) Math.ceil(demand * overTolerancePercent / 100.0) - 1);
            double overPenaltyMultiplier = isFinishedProduct ? (itemTypeMultiplier * 100.0) : (itemTypeMultiplier * 3.0);  // 成品超产惩罚：100,000倍
            int overPenalty = (int)(Math.max(0, over - tolerated) * OVER_PENALTY_WEIGHT * priorityWeight * overPenaltyMultiplier);

            cachedContribution = propReward + completeReward + finishedProductOnTimeBonus - unmetPenalty - overPenalty;
            return cachedContribution;
        }
        
        /**
         * 根据物料类型自动选择容忍度重新计算贡献（使用不同的成品和部件容忍度）
         */
        int recomputeContributionWithDifferentTolerances(int finishedProductTolerance, int componentTolerance) {
            // 根据物料类型选择合适的容忍度
            int applicableTolerance = isFinishedProductBucket() ? finishedProductTolerance : componentTolerance;
            return recomputeContribution(applicableTolerance);
        }
    }
    
    // ----------------- 订单级迟期惩罚 -----------------

    /**
     * 基于“完成该需求的最早时间槽”计算迟期（按时间槽数），
     * 再按照优先级权重和物料类型加权，形成订单级软惩罚。
     */
    private int computeTardinessPenalty(DemandBucket db) {
        int[] produced = producedPerSlot.get(db.item);
        if (produced == null || produced.length == 0) {
            return 0;
        }

        int target = db.prevDemandSum + db.demand; // 累计产量达到该值视为此桶“完成”
        int cum = 0;
        int completionIndex = -1;
        for (int i = 0; i < slotCount; i++) {
            cum += produced[i];
            if (cum >= target) {
                completionIndex = i;
                break;
            }
        }

        // 与桶内打分使用同一优先级权重曲线
        double priorityWeight = 0.5 + (db.priority - 1) * 0.28;

        boolean isFinishedProduct = (db.item.getItemType() == com.iimsoft.scheduler.domain.ItemType.FINISHED_PRODUCT);
        int weightPerSlot = isFinishedProduct ? FINISHED_TARDINESS_WEIGHT : COMPONENT_TARDINESS_WEIGHT;

        int tardinessSlots;
        if (completionIndex < 0) {
            // 截止计划结束都未完成：按"从时间窗口最晚完成到最后一个槽"的迟期计分
            int lastIdx = Math.max(0, slotCount - 1);
            tardinessSlots = Math.max(0, lastIdx - db.latestFinishSlot + 1);
        } else {
            // 基于时间窗口检查：如果完成时间超出最晚完成时间，则计算迟期
            tardinessSlots = Math.max(0, completionIndex - db.latestFinishSlot);
        }

        if (tardinessSlots <= 0) {
            return 0;
        }

        // 使用指数增长的迟期惩罚：迟期越久，惩罚增长越快
        // 公式：tardinessSlots * weightPerSlot * priorityWeight * (1.5^tardinessSlots)
        double exponentialFactor = Math.pow(1.5, Math.min(tardinessSlots, 10));  // 限制指数防止溢出
        return (int) (tardinessSlots * weightPerSlot * priorityWeight * exponentialFactor);
    }
    
    // ----------------- 工序依赖约束 -----------------
    
    /**
     * 计算整个排程中工序依赖违反的次数
     */
    private int calculatePredecessorViolations(ProductionSchedule solution) {
        int violations = 0;
        for (ProductionAssignment a : solution.getAssignmentList()) {
            if (a.getRouter() != null) {
                violations += countPredecessorViolationsForAssignment(a, a.getRouter());
            }
        }
        return violations;
    }
    
    /**
     * 计算单个Assignment的工序依赖违反数
     * 检查规则：当前工序的所有前置工序必须在当前工序开始之前完成
     */
    private int countPredecessorViolationsForAssignment(ProductionAssignment assignment, Router router) {
        if (router == null || !router.hasPredecessors()) {
            return 0;
        }
        
        int currentStartTime = assignment.getTimeSlot().getIndex();
        int violations = 0;
        
        // 检查每个前置工序
        for (Router predecessor : router.getPredecessors()) {
            // 找到前置工序的最后完成时间
            int predecessorLastTime = findLastTimeSlotForRouter(predecessor);
            
            // 如果前置工序没有被排程，或者完成时间 >= 当前开始时间，则违反约束
            if (predecessorLastTime < 0 || predecessorLastTime >= currentStartTime) {
                violations++;
            }
        }
        
        return violations;
    }
    
    /**
     * 找到某个工序最后被排程的时间槽索引
     * @return 最后时间槽索引，如果未排程则返回-1
     */
    private int findLastTimeSlotForRouter(Router router) {
        int lastIndex = -1;
        for (ProductionAssignment a : solution.getAssignmentList()) {
            if (router.equals(a.getRouter())) {
                int idx = a.getTimeSlot().getIndex();
                if (idx > lastIndex) {
                    lastIndex = idx;
                }
            }
        }
        return lastIndex;
    }
}