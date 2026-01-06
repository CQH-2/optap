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

    private static final int HOLDING_COST_WEIGHT     = 3;      // 库存持有成本：超安全库存的件×时槽×权重（降低以允许适度库存）
    private static final int SAFETY_SHORTAGE_WEIGHT  = 10;     // 低于安全库存的惩罚：(安全库存-实际库存)×时槽×权重
    private static final int CHANGEOVER_PENALTY      = 1500;   // 相邻时槽工艺切换惩罚（同线）（提高3倍，鼓励批量生产）
    private static final int BATCH_PRODUCTION_REWARD = 200;    // 连续生产同一工艺的奖励（每对相邻时槽）
    private static final int HARD_UNSUPPORTED_WEIGHT = 1_000_000; // 产线不支持工艺的硬惩罚
    private static final int NIGHT_SHIFT_PENALTY     = 100;    // 夜班额外成本惩罚（每件）
    private static final int HARD_PREDECESSOR_VIOLATION = 1_000_000; // 工序依赖违反的硬惩罚
    private static final int HARD_UNMET_DEMAND_WEIGHT = 10;    // 未满足需求的硬约束权重（每件）
    private static final int HARD_BOM_SHORTAGE_WEIGHT = 10;    // BOM消耗不足的硬约束权重（每件）
    
    // 参数化配置（可在运行时修改）
    private int overTolerancePercent = 1; // 超产容忍度（百分比）（降低到1%以减少超产）

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
    private int hardUnmetDemand;   // 未满足需求数累计（硬约束）（>0）
    private int hardBomShortage;   // BOM消耗超产量硬约束（>0）
    
    // 公开setter方法，允许运行时配置
    public void setOverTolerancePercent(int percent) {
        this.overTolerancePercent = Math.max(0, Math.min(100, percent));
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

        // 构建需求桶并计算软分
        buildDemandBuckets();
        softBucketScore = 0;
        for (List<DemandBucket> buckets : demandsByItem.values()) {
            for (DemandBucket db : buckets) {
                softBucketScore += db.recomputeContribution(overTolerancePercent);
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
        
        // 需求满足硬约束：计算未满足需求数
        hardUnmetDemand = 0;
        for (List<DemandBucket> buckets : demandsByItem.values()) {
            for (DemandBucket db : buckets) {
                int available = Math.max(0, db.producedCumAtDue - db.prevDemandSum);
                int unmet = Math.max(0, db.demand - available);
                hardUnmetDemand += unmet;
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
        
        int hard = -hardPenalty - hardUnsupportedPenalty - (predecessorViolations * HARD_PREDECESSOR_VIOLATION) - (hardUnmetDemand * 100) - (hardBomShortage * 100);
        int soft = softBucketScore - holdingPenalty - safetyShortage - changeoverPenalty + batchProductionReward - nightShiftCost;
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
                hardUnmetDemand += (newUnmet - prevUnmet);
                
                softBucketScore += db.recomputeContribution(overTolerancePercent);
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
    
    // 批量生产奖励：相同工艺连续生产给予奖励
    private int batchReward(ProductionAssignment a1, ProductionAssignment a2) {
        if (a1 == null || a2 == null) return 0;
        Router r1 = a1.getRouter();
        Router r2 = a2.getRouter();
        if (r1 == null || r2 == null) return 0;
        return Objects.equals(r1, r2) ? BATCH_PRODUCTION_REWARD : 0;
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
                db.recomputeContribution(overTolerancePercent);
                buckets.add(db);
                prevSum += d.getQuantity();
            }
            demandsByItem.put(item, buckets);
        }
    }

    private int sumProducedUpTo(Item item, int dueIndex) {
        int[] arr = producedPerSlot.get(item);
        int end = Math.min(dueIndex, arr.length - 1);
        int sum = 0;
        for (int i = 0; i <= end; i++) sum += arr[i];
        return sum;
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

        DemandBucket(Item item, int demand, int dueIndex, int prevDemandSum, int producedCumAtDue, int priority) {
            this.item = item;
            this.demand = demand;
            this.dueIndex = dueIndex;
            this.prevDemandSum = prevDemandSum;
            this.producedCumAtDue = producedCumAtDue;
            this.priority = priority > 0 ? priority : 5; // 默认优先级为5
        }

        int recomputeContribution(int overTolerancePercent) {
            // 可用量（净额）：自身累计产量 - 之前桶需求和
            int available = Math.max(0, producedCumAtDue - prevDemandSum);

            // 优先级权重：指数曲线，使高优先级影响更显著
            // priority=1: 0.5, priority=5: 1.6, priority=10: 3.0
            double priorityWeight = 0.5 + (priority - 1) * 0.28;

            // 比例奖励（封顶），乘以优先级权重
            int propReward = demand <= 0 ? 0 : (int)(((Math.min(available, demand) * 1000) / demand) * PROP_REWARD_WEIGHT * priorityWeight);

            // 完全满足奖励（不超容忍度），乘以优先级权重
            int maxAcceptable = (int) Math.ceil(demand * (1.0 + overTolerancePercent / 100.0));
            int completeReward = (available >= demand && available <= maxAcceptable) ? (int)(COMPLETE_REWARD_BONUS * priorityWeight) : 0;

            // 未满足惩罚，乘以优先级权重（高优先级未满足惩罚更重）
            int unmet = Math.max(0, demand - available);
            int unmetPenalty = (int)(unmet * UNMET_PENALTY_WEIGHT * priorityWeight);

            // 超产惩罚（超过容忍阈值部分），乘以优先级权重
            int over = Math.max(0, available - demand);
            int tolerated = Math.max(0, (int) Math.ceil(demand * overTolerancePercent / 100.0) - 1);
            int overPenalty = (int)(Math.max(0, over - tolerated) * OVER_PENALTY_WEIGHT * priorityWeight);

            cachedContribution = propReward + completeReward - unmetPenalty - overPenalty;
            return cachedContribution;
        }
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