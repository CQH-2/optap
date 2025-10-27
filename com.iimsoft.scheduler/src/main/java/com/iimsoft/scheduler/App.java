package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class App {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        // 准备示例数据
        ExampleData data = buildExampleData();

        // 构建初始解（所有 assignment 的 router 为空）
        ProductionSchedule problem = new ProductionSchedule(
                data.routers,
                data.lines,
                data.timeSlots,
                data.bomArcs,
                data.inventories,
                data.demands,
                data.assignments
        );

        SolverFactory<ProductionSchedule> solverFactory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        var solverManager = SolverManager.create(solverFactory);
        long problemId = 1L;

        ProductionSchedule solution = solverManager.solve(problemId, problem).getFinalBestSolution();

        // 新增：打印约束解释 summary，快速看哪条约束在起作用
        var scoreManager = org.optaplanner.core.api.score.ScoreManager.create(solverFactory);
        System.out.println(scoreManager.explain(solution).getSummary());

        System.out.println("Score: " + solution.getScore());

        printSchedule(solution);
//        printComponentInventoryTimeline(solution);
    }

    private static void printSchedule(ProductionSchedule solution) {
        System.out.println("==== 物料信息 ====");
        for (Item item : solution.getRouterList().stream().map(Router::getItem).distinct().toList()) {
            System.out.printf("物料编码：%s，名称：%s%n", item.getCode(), item.getName());
        }

        System.out.println("\n==== BOM结构 ====");
        for (BomArc arc : solution.getBomArcList()) {
            System.out.printf("总成物料：%s 需要 子件：%s 数量：%d%n",
                    arc.getParent().getCode(), arc.getChild().getCode(), arc.getQuantityPerParent());
        }

        System.out.println("\n==== 初始库存 ====");
        for (ItemInventory inv : solution.getInventoryList()) {
            System.out.printf("物料：%s 初始在库：%d%n", inv.getItem().getCode(), inv.getInitialOnHand());
        }

        System.out.println("\n==== 生产线与工艺能力 ====");
        for (ProductionLine line : solution.getLineList()) {
            System.out.printf("生产线：%s 支持工艺：", line.getCode());
            System.out.print(line.getSupportedRouters().stream()
                    .map(r -> String.format("%s(物料:%s，速度:%d件/小时)", r.getCode(), r.getItem().getCode(), r.getSpeedPerHour()))
                    .collect(Collectors.joining("，")));
            System.out.println();
        }

        System.out.println("\n==== 物料需求 ====");
        for (DemandOrder d : solution.getDemandList()) {
            System.out.printf("需求：物料%s，数量%d，截止日期%s，截止时间槽索引#%d%n",
                    d.getItem().getCode(), d.getQuantity(), d.getDueDate(), d.getDueTimeSlotIndex());
        }

        // 全量物料集合（用于“全局库存/变动”展示）
        List<Item> allItems = collectAllItems(solution);

        // 计算“每个时间槽结束时”的全局库存快照
        Map<Integer, Map<Item, Integer>> globalInvSnapshots = computeGlobalInventorySnapshots(solution, allItems);
        // 计算“每个时间槽的全局库存变动”（相对于上一个时间槽；首槽相对于初始）
        Map<Integer, Map<Item, Integer>> globalInvDeltas = computeGlobalInventoryDeltas(solution, allItems, globalInvSnapshots);

        // 预备：父件->BOM子件列表，用于计算“本行影响”
        Map<Item, List<BomArc>> parentToArcs = solution.getBomArcList().stream()
                .collect(Collectors.groupingBy(BomArc::getParent));

        System.out.println("\n==== 调度结果（本行影响 + 全局库存变动 + 全局库存） ====");
        var assignments = new ArrayList<>(solution.getAssignmentList());
        assignments.sort(Comparator.comparing((ProductionAssignment a) -> a.getTimeSlot().getIndex()).thenComparing(a -> a.getLine().getCode()));
        for (ProductionAssignment a : assignments) {
            String line = a.getLine().getCode();
            String time = a.getTimeSlot().toString();
            String router = a.getRouter() == null ? "空闲" : a.getRouter().getCode();
            String item = a.getProducedItem() == null ? "-" : a.getProducedItem().getCode();
            int qty = a.getProducedQuantity();

            // 本行对库存的直接影响（仅由该 assignment 引起）
            Map<Item, Integer> rowDelta = new LinkedHashMap<>();
            if (a.getRouter() != null) {
                Item produced = a.getProducedItem();
                // 该物料入库
                if (produced != null) {
                    rowDelta.merge(produced, qty, Integer::sum);
                }
                // 若是父件，则消耗其子件
                for (BomArc arc : parentToArcs.getOrDefault(produced, List.of())) {
                    rowDelta.merge(arc.getChild(), -qty * arc.getQuantityPerParent(), Integer::sum);
                }
            }
            String rowDeltaStr = rowDelta.entrySet().stream()
                    .filter(e -> e.getValue() != 0)
                    .map(e -> e.getKey().getCode() + (e.getValue() >= 0 ? ":+":"") + e.getValue())
                    .collect(Collectors.joining(","));
            if (rowDeltaStr.isEmpty()) rowDeltaStr = "-";

            int idx = a.getTimeSlot().getIndex();

            // 全局库存变动（本槽相对上一槽）
            Map<Item, Integer> deltaAtSlot = globalInvDeltas.getOrDefault(idx, Map.of());
            String slotDeltaStr = deltaAtSlot.entrySet().stream()
                    .filter(e -> e.getValue() != 0)
                    .map(e -> e.getKey().getCode() + (e.getValue() >= 0 ? ":+":"") + e.getValue())
                    .collect(Collectors.joining(","));
            if (slotDeltaStr.isEmpty()) slotDeltaStr = "-";

            // 全局库存（该时间槽结束时）- 全部物料
            Map<Item, Integer> invAtSlot = globalInvSnapshots.getOrDefault(idx, Map.of());
            String invStr = allItems.stream()
                    .map(it -> it.getCode() + "=" + invAtSlot.getOrDefault(it, 0))
                    .collect(Collectors.joining(","));

            System.out.printf("%s | 生产线：%s | 工艺：%-8s | 生产物料：%-4s 数量：%d | 本行影响[%s] | 全局库存Δ[%s] | 全局库存[%s]%n",
                    time, line, router, item, qty, rowDeltaStr, slotDeltaStr, invStr);
        }

        // 汇总产量
        Map<Item, Integer> producedByItem = assignments.stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(ProductionAssignment::getProducedItem,
                        Collectors.summingInt(ProductionAssignment::getProducedQuantity)));
        System.out.println("\n==== 产量汇总 ====");
        producedByItem.forEach((it, sum) ->
                System.out.printf("物料%s 总产量：%d%n", it.getCode(), sum));
    }

    // 打印“子件库存逐时演进”，让你看到在父件生产时子件是否够（保持原有输出）
    private static void printComponentInventoryTimeline(ProductionSchedule solution) {
        System.out.println("\n==== 子件库存逐时演进（CSV）====");
        // 只统计作为“子件”出现过的物料
        Set<Item> componentItems = solution.getBomArcList().stream()
                .map(BomArc::getChild)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Item, Integer> initial = solution.getInventoryList().stream()
                .collect(Collectors.toMap(ItemInventory::getItem, ItemInventory::getInitialOnHand));

        // 为保证顺序，按 index 排序
        List<TimeSlot> orderedSlots = new ArrayList<>(solution.getTimeSlotList());
        orderedSlots.sort(Comparator.comparingInt(TimeSlot::getIndex));

        // 预聚合：每个时段每个物料的产量
        Map<Integer, Map<Item, Integer>> producedAtSlot = solution.getAssignmentList().stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(a -> a.getTimeSlot().getIndex(),
                        Collectors.groupingBy(ProductionAssignment::getProducedItem,
                                Collectors.summingInt(ProductionAssignment::getProducedQuantity))));

        // 预聚合：每个时段每个“父物料”的产量（用于子件消耗计算）
        Map<Integer, Map<Item, Integer>> parentProducedAtSlot = producedAtSlot;

        // BOM映射：子件 -> 其消耗项列表（父物料、用量）
        Map<Item, List<BomArc>> childToArcs = solution.getBomArcList().stream()
                .collect(Collectors.groupingBy(BomArc::getChild));

        // CSV 头
        System.out.println("date,hour,timeSlotIndex,item,begin_inv,produced,consumed,end_inv,warning");
        for (Item child : componentItems) {
            int onHand = initial.getOrDefault(child, 0);
            for (TimeSlot ts : orderedSlots) {
                int idx = ts.getIndex();
                int produced = producedAtSlot
                        .getOrDefault(idx, Map.of())
                        .getOrDefault(child, 0);
                int consumed = 0;
                for (BomArc arc : childToArcs.getOrDefault(child, List.of())) {
                    int parentQty = parentProducedAtSlot
                            .getOrDefault(idx, Map.of())
                            .getOrDefault(arc.getParent(), 0);
                    consumed += parentQty * arc.getQuantityPerParent();
                }
                int end = onHand + produced - consumed;
                String warning = end < 0 ? "NEGATIVE_INV" : "";
                System.out.printf("%s,%02d,%d,%s,%d,%d,%d,%d,%s%n",
                        ts.getDate(), ts.getHour(), idx, child.getCode(),
                        onHand, produced, consumed, end, warning);
                onHand = end;
            }
        }
    }

    // 收集全量物料集合（库存/路由/BOM/需求中出现过的所有物料）
    private static List<Item> collectAllItems(ProductionSchedule solution) {
        LinkedHashSet<Item> set = new LinkedHashSet<>();
        set.addAll(solution.getInventoryList().stream().map(ItemInventory::getItem).toList());
        set.addAll(solution.getRouterList().stream().map(Router::getItem).toList());
        set.addAll(solution.getBomArcList().stream()
                .flatMap(arc -> Stream.of(arc.getParent(), arc.getChild()))
                .toList());
        set.addAll(solution.getDemandList().stream().map(DemandOrder::getItem).toList());
        return new ArrayList<>(set);
    }

    // 计算“每个时间槽结束时”的全局库存快照（包含所有物料）
    private static Map<Integer, Map<Item, Integer>> computeGlobalInventorySnapshots(ProductionSchedule solution, List<Item> allItems) {
        // 初始库存（所有物料，默认0）
        Map<Item, Integer> currentOnHand = new HashMap<>();
        for (Item it : allItems) {
            int initial = solution.getInventoryList().stream()
                    .filter(inv -> inv.getItem().equals(it))
                    .mapToInt(ItemInventory::getInitialOnHand)
                    .findFirst()
                    .orElse(0);
            currentOnHand.put(it, initial);
        }

        // 时间槽有序
        List<TimeSlot> orderedSlots = new ArrayList<>(solution.getTimeSlotList());
        orderedSlots.sort(Comparator.comparingInt(TimeSlot::getIndex));

        // 每槽每物料的产量
        Map<Integer, Map<Item, Integer>> producedAtSlot = solution.getAssignmentList().stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(a -> a.getTimeSlot().getIndex(),
                        Collectors.groupingBy(ProductionAssignment::getProducedItem,
                                Collectors.summingInt(ProductionAssignment::getProducedQuantity))));

        // BOM 列表
        List<BomArc> arcs = solution.getBomArcList();

        Map<Integer, Map<Item, Integer>> snapshot = new LinkedHashMap<>();
        for (TimeSlot ts : orderedSlots) {
            int idx = ts.getIndex();

            // 本槽产出：所有物料直接入库
            Map<Item, Integer> producedThisSlot = producedAtSlot.getOrDefault(idx, Map.of());
            for (Map.Entry<Item, Integer> e : producedThisSlot.entrySet()) {
                if (e.getValue() != 0) {
                    currentOnHand.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }

            // 子件消耗：由父件产量驱动（同槽内先入库再消耗）
            Map<Item, Integer> consumedChild = new HashMap<>();
            for (BomArc arc : arcs) {
                int parentQty = producedThisSlot.getOrDefault(arc.getParent(), 0);
                if (parentQty != 0) {
                    consumedChild.merge(arc.getChild(), parentQty * arc.getQuantityPerParent(), Integer::sum);
                }
            }
            for (Map.Entry<Item, Integer> e : consumedChild.entrySet()) {
                currentOnHand.merge(e.getKey(), -e.getValue(), Integer::sum);
            }

            // 记录该槽结束时的全局库存
            Map<Item, Integer> endInv = new LinkedHashMap<>();
            for (Item it : allItems) {
                endInv.put(it, currentOnHand.getOrDefault(it, 0));
            }
            snapshot.put(idx, endInv);
        }
        return snapshot;
    }

    // 计算“每个时间槽的全局库存变动”（相对上一个时间槽；首槽相对于初始）
    private static Map<Integer, Map<Item, Integer>> computeGlobalInventoryDeltas(ProductionSchedule solution,
                                                                                 List<Item> allItems,
                                                                                 Map<Integer, Map<Item, Integer>> snapshots) {
        // 初始库存 map（用于首槽的对比）
        Map<Item, Integer> prev = new HashMap<>();
        for (Item it : allItems) {
            int initial = solution.getInventoryList().stream()
                    .filter(inv -> inv.getItem().equals(it))
                    .mapToInt(ItemInventory::getInitialOnHand)
                    .findFirst()
                    .orElse(0);
            prev.put(it, initial);
        }

        List<TimeSlot> orderedSlots = new ArrayList<>(solution.getTimeSlotList());
        orderedSlots.sort(Comparator.comparingInt(TimeSlot::getIndex));

        Map<Integer, Map<Item, Integer>> deltas = new LinkedHashMap<>();
        for (TimeSlot ts : orderedSlots) {
            int idx = ts.getIndex();
            Map<Item, Integer> snap = snapshots.getOrDefault(idx, Map.of());

            Map<Item, Integer> delta = new LinkedHashMap<>();
            for (Item it : allItems) {
                int d = snap.getOrDefault(it, 0) - prev.getOrDefault(it, 0);
                delta.put(it, d);
            }
            deltas.put(idx, delta);

            // 更新 prev（用一份副本，防止后续修改引用）
            Map<Item, Integer> nextPrev = new HashMap<>();
            for (Item it : allItems) {
                nextPrev.put(it, snap.getOrDefault(it, 0));
            }
            prev = nextPrev;
        }
        return deltas;
    }

    // ====== 示例数据生成 ======
    static class ExampleData {
        List<Item> items;
        List<BomArc> bomArcs;
        List<Router> routers;
        List<ProductionLine> lines;
        List<TimeSlot> timeSlots;
        List<ItemInventory> inventories;
        List<DemandOrder> demands;
        List<ProductionAssignment> assignments;
    }

    private static ExampleData buildExampleData() {
        ExampleData d = new ExampleData();

        // 物料
        Item A = new Item("A", "Assembly A", 0); // 总成提前期0
        Item B = new Item("B", "Part B", 1);     // B提前期1天
        Item C = new Item("C", "Part C", 2);     // C提前期2天
        Item X = new Item("X", "Assembly X", 0);
        Item D = new Item("D", "Part D", 1);
        Item E = new Item("E", "Part E", 1);
        d.items = List.of(A, B, C, X, D, E);

        // BOM：
        // A = 2*B + 1*C
        BomArc arcAB = new BomArc(A, B, 2);
        BomArc arcAC = new BomArc(A, C, 1);
        // X = 1*D + 1*E
        BomArc arcXD = new BomArc(X, D, 1);
        BomArc arcXE = new BomArc(X, E, 1);
        d.bomArcs = List.of(arcAB, arcAC, arcXD, arcXE);

        // 生产线
        ProductionLine L1 = new ProductionLine("L1");
        ProductionLine L2 = new ProductionLine("L2");
        d.lines = List.of(L1, L2);

        // 工艺（速度：件/小时）
        Router rA1 = new Router("rA1", A, 10);
        Router rA2 = new Router("rA2", A, 8);
        Router rB1 = new Router("rB1", B, 18);
        Router rC1 = new Router("rC1", C, 14);
        // 新增：X、D、E 的工艺
        Router rX1 = new Router("rX1", X, 9);
        Router rD1 = new Router("rD1", D, 16);
        Router rE1 = new Router("rE1", E, 12);
        d.routers = List.of(rA1, rA2, rB1, rC1, rX1, rD1, rE1);

        // 产线支持的工艺（可按需分配）
        L1.setSupportedRouters(List.of(rA1, rB1, rD1, rX1));
        L2.setSupportedRouters(List.of(rA2, rC1, rE1));

        // 时间槽：两天 8-19（原样）
        LocalDate day1 = LocalDate.now();
        LocalDate day2 = day1.plusDays(1);
        List<TimeSlot> slots = new ArrayList<>();
        int index = 0;
        for (LocalDate d0 : List.of(day1, day2)) {
            for (int h = 8; h <= 19; h++) {
                slots.add(new TimeSlot(d0, h, index++));
            }
        }
        d.timeSlots = slots;

        // 初始库存（可按需调整；新增 X/D/E 的库存）
        d.inventories = List.of(
                new ItemInventory(A, 0),
                new ItemInventory(B, 50),
                new ItemInventory(C, 20),
                new ItemInventory(X, 0),
                new ItemInventory(D, 30),
                new ItemInventory(E, 15)
        );

        // 需求：A、X 都给一个需求例子
        int dueIndexDay1 = slots.stream()
                .filter(s -> s.getDate().equals(day1))
                .mapToInt(TimeSlot::getIndex)
                .max()
                .orElse(11);
        int dueIndexDay2 = slots.stream()
                .filter(s -> s.getDate().equals(day2))
                .mapToInt(TimeSlot::getIndex)
                .max()
                .orElse(dueIndexDay1 + 12);

        DemandOrder demandA = new DemandOrder(A, 500, day1, dueIndexDay1);
        DemandOrder demandX = new DemandOrder(X, 300, day2, dueIndexDay2);

        // =========== 预分解子料需求 ===========

        List<DemandOrder> origDemands = List.of(demandA, demandX);
        Map<Item, Integer> itemToTotalDemand = new LinkedHashMap<>();
        Map<Item, LocalDate> itemToDueDate = new HashMap<>();
        Map<Item, Integer> itemToDueSlotIndex = new HashMap<>();

// 1. 统计所有需求（含BOM分解，含安全库存）
        for (DemandOrder d0 : origDemands) {
            itemToTotalDemand.merge(d0.getItem(), d0.getQuantity(), Integer::sum);
            itemToDueDate.put(d0.getItem(), d0.getDueDate());
            itemToDueSlotIndex.put(d0.getItem(), d0.getDueTimeSlotIndex());
            // 分解BOM（仅一层，若需多层递归可扩展）
            for (BomArc arc : d.bomArcs) {
                if (arc.getParent().equals(d0.getItem())) {
                    int childNeed = d0.getQuantity() * arc.getQuantityPerParent();
                    Item child = arc.getChild();
                    int lead = child.getLeadTime();
                    LocalDate childDueDate = d0.getDueDate().minusDays(lead);
                    int childDueSlotIndex = d.timeSlots.stream()
                            .filter(s -> s.getDate().equals(childDueDate))
                            .mapToInt(TimeSlot::getIndex)
                            .max()
                            .orElse(d0.getDueTimeSlotIndex());
                    itemToTotalDemand.merge(child, childNeed, Integer::sum);
                    itemToDueDate.put(child, childDueDate);
                    itemToDueSlotIndex.put(child, childDueSlotIndex);
                }
            }
        }

// 2. 统计安全库存（所有物料）
        for (ItemInventory inv : d.inventories) {
            int safety = inv.getSafetyStock();
            if (safety > 0) {
                itemToTotalDemand.merge(inv.getItem(), safety, Integer::sum);
                // 如果没有截止时间，则默认最晚的一个
                itemToDueDate.putIfAbsent(inv.getItem(), day2); // 你可自定义策略
                itemToDueSlotIndex.putIfAbsent(inv.getItem(), d.timeSlots.size() - 1);
            }
        }

// 3. 扣减初始库存，剩余才需生产（不能小于0）
        List<DemandOrder> allDemands = new ArrayList<>();
        for (Item item : itemToTotalDemand.keySet()) {
            int totalDemand = itemToTotalDemand.get(item);
            int initial = d.inventories.stream()
                    .filter(inv -> inv.getItem().equals(item))
                    .mapToInt(ItemInventory::getInitialOnHand)
                    .findFirst().orElse(0);
            int needToProduce = totalDemand - initial;
            if (needToProduce > 0) {
                allDemands.add(new DemandOrder(
                        item,
                        needToProduce,
                        itemToDueDate.get(item),
                        itemToDueSlotIndex.get(item)
                ));
            }
        }
        d.demands = allDemands;

        // 规划实体：每条产线 * 每个时间槽
        List<ProductionAssignment> assignments = new ArrayList<>();
        long id = 1L;
        for (ProductionLine line : d.lines) {
            for (TimeSlot ts : d.timeSlots) {
                assignments.add(new ProductionAssignment(id++, line, ts));
            }
        }
        d.assignments = assignments;

        return d;
    }
}