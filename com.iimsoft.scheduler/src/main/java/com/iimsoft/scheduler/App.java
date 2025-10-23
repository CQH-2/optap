package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

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

        System.out.println("Score: " + solution.getScore());
        printSchedule(solution);
        printComponentInventoryTimeline(solution);
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

        System.out.println("\n==== 调度结果 ====");
        var assignments = new ArrayList<>(solution.getAssignmentList());
        assignments.sort(Comparator
                .comparing((ProductionAssignment a) -> a.getTimeSlot().getIndex())
                .thenComparing(a -> a.getLine().getCode()));
        for (ProductionAssignment a : assignments) {
            String line = a.getLine().getCode();
            String time = a.getTimeSlot().toString();
            String router = a.getRouter() == null ? "空闲" : a.getRouter().getCode();
            String item = a.getProducedItem() == null ? "-" : a.getProducedItem().getCode();
            int qty = a.getProducedQuantity();
            System.out.printf("%s | 生产线：%s | 工艺：%-8s | 生产物料：%-4s 数量：%d%n", time, line, router, item, qty);
        }

        // 汇总产量
        Map<Item, Integer> producedByItem = assignments.stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(ProductionAssignment::getProducedItem,
                        Collectors.summingInt(ProductionAssignment::getProducedQuantity)));
        System.out.println("\n==== 产量汇总 ====");
        producedByItem.forEach((item, sum) ->
                System.out.printf("物料%s 总产量：%d%n", item.getCode(), sum));
    }

    // 打印“子件库存逐时演进”，让你看到在父件生产时子件是否够
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
        Item A = new Item("A", "Assembly A");
        Item B = new Item("B", "Part B");
        Item C = new Item("C", "Part C");
        d.items = List.of(A, B, C);

        // BOM：A = 2*B + 1*C
        BomArc arcAB = new BomArc(A, B, 2);
        BomArc arcAC = new BomArc(A, C, 1);
        d.bomArcs = List.of(arcAB, arcAC);

        // 生产线
        ProductionLine L1 = new ProductionLine("L1");
        ProductionLine L2 = new ProductionLine("L2");
        d.lines = List.of(L1, L2);

        // 工艺（速度：件/小时）
        Router rA1 = new Router("rA1", A, 10);
        Router rA2 = new Router("rA2", A, 8);
        Router rB1 = new Router("rB1", B, 18);
        Router rC1 = new Router("rC1", C, 14);
        d.routers = List.of(rA1, rA2, rB1, rC1);

        // 产线支持的工艺
        L1.setSupportedRouters(List.of(rA1, rB1));
        L2.setSupportedRouters(List.of(rA2, rC1));

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

        // 初始库存（可按需调整）
        d.inventories = List.of(
                new ItemInventory(A, 0),
                new ItemInventory(B, 50),
                new ItemInventory(C, 20)
        );

        // 需求：只对A设需求，子件不直接设需求
        int dueIndexDay1 = slots.stream()
                .filter(s -> s.getDate().equals(day1))
                .mapToInt(TimeSlot::getIndex)
                .max()
                .orElse(11);
        DemandOrder demandA = new DemandOrder(A, 50, day1, dueIndexDay1);
        d.demands = List.of(demandA);

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