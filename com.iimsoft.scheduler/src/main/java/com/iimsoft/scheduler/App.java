package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.service.DataBuildService;
import com.iimsoft.scheduler.service.IOService;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class App {

    public static void main(String[] args) throws Exception {
// 1. 指定资源名
        String jsonFileName = "example_data.json";
        // 2. 获取resources下的文件路径
        ClassLoader classLoader = App.class.getClassLoader();
        String jsonFilePath = Objects.requireNonNull(classLoader.getResource(jsonFileName)).getPath();

        // 3. 用DataBuildService加载
        DataBuildService dataBuildService = new DataBuildService();
        ProductionSchedule problem = dataBuildService.buildScheduleFromFile(jsonFilePath);

        SolverFactory<ProductionSchedule> solverFactory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        var solverManager = SolverManager.create(solverFactory);
        long problemId = 1L;

        ProductionSchedule solution = solverManager.solve(problemId, problem).getFinalBestSolution();

        // 打印约束解释 summary
        var scoreManager = org.optaplanner.core.api.score.ScoreManager.create(solverFactory);
//        IOService.exportScheduleToCsv(solution, "schedule_output.csv");

//        System.out.println(scoreManager.explain(solution).getSummary());
//
//        System.out.println("Score: " + solution.getScore());

        printSchedule(solution);
//
//        // 新增：导出为 CSV 风格的汇总（按 生产线、日期、物料，小时连续合并为时间段）
//        printCsvMergedByLineDateItem(solution);
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

        // -------- 按生产线分组输出（明细） --------
        System.out.println("\n==== 调度结果（按生产线分组，明细） ====");
        // 按产线分组，组内按时间排序
        Map<ProductionLine, List<ProductionAssignment>> assignmentsByLine = solution.getAssignmentList().stream()
                .collect(Collectors.groupingBy(ProductionAssignment::getLine));
        // 保持产线顺序
        for (ProductionLine line : solution.getLineList()) {
            System.out.printf("---- 生产线：%s ----%n", line.getCode());
            List<ProductionAssignment> assignments = assignmentsByLine.getOrDefault(line, List.of());
            // 组内按时间顺序
            assignments = assignments.stream()
                    .sorted(Comparator.comparing(a -> a.getTimeSlot().getIndex()))
                    .toList();

            for (ProductionAssignment a : assignments) {
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

                System.out.printf("%s | 工艺：%-8s | 生产物料：%-4s 数量：%d | 本行影响[%s] | 全局库存Δ[%s] | 全局库存[%s]%n",
                        time, router, item, qty, rowDeltaStr, slotDeltaStr, invStr);
            }
            System.out.println();
        }

        // 汇总产量
        Map<Item, Integer> producedByItem = solution.getAssignmentList().stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(ProductionAssignment::getProducedItem,
                        Collectors.summingInt(ProductionAssignment::getProducedQuantity)));
        System.out.println("==== 产量汇总 ====");
        producedByItem.forEach((it, sum) ->
                System.out.printf("物料%s 总产量：%d%n", it.getCode(), sum));
    }

    // 新增：CSV 输出（按 生产线、日期、物料，连续小时段合并）
    private static void printCsvMergedByLineDateItem(ProductionSchedule solution) {
        System.out.println("\n==== CSV（按 生产线、日期、物料，合并小时）====");
        System.out.println("line,date,item,quantity,time");

        // 分组：生产线 -> 日期 -> 物料
        Map<ProductionLine, Map<LocalDate, Map<Item, List<ProductionAssignment>>>> grouped =
                solution.getAssignmentList().stream()
                        .filter(a -> a.getRouter() != null && a.getProducedItem() != null && a.getProducedQuantity() > 0)
                        .collect(Collectors.groupingBy(
                                ProductionAssignment::getLine,
                                Collectors.groupingBy(
                                        a -> a.getTimeSlot().getDate(),
                                        Collectors.groupingBy(ProductionAssignment::getProducedItem)
                                )
                        ));

        // 保持生产线顺序输出
        for (ProductionLine line : solution.getLineList()) {
            Map<LocalDate, Map<Item, List<ProductionAssignment>>> byDate = grouped.get(line);
            if (byDate == null || byDate.isEmpty()) continue;

            // 日期升序
            List<LocalDate> dates = new ArrayList<>(byDate.keySet());
            Collections.sort(dates);

            for (LocalDate date : dates) {
                Map<Item, List<ProductionAssignment>> byItem = byDate.get(date);
                // 物料按编码排序
                List<Item> items = new ArrayList<>(byItem.keySet());
                items.sort(Comparator.comparing(Item::getCode));

                for (Item item : items) {
                    List<ProductionAssignment> ass = byItem.get(item).stream()
                            .sorted(Comparator.comparingInt(a -> a.getTimeSlot().getIndex()))
                            .toList();

                    // 合并连续小时段
                    ProductionAssignment start = null;
                    ProductionAssignment prev = null;
                    int sumQty = 0;

                    for (ProductionAssignment a : ass) {
                        if (start == null) {
                            start = a;
                            prev = a;
                            sumQty = a.getProducedQuantity();
                            continue;
                        }
                        if (a.getTimeSlot().getIndex() == prev.getTimeSlot().getIndex() + 1) {
                            // 连续
                            sumQty += a.getProducedQuantity();
                            prev = a;
                        } else {
                            // 断开，输出上一段
                            String time = toTimeRange(start.getTimeSlot(), prev.getTimeSlot());
                            System.out.printf("%s,%s,%s,%d,%s%n",
                                    line.getCode(), date, item.getCode(), sumQty, time);
                            // 重置
                            start = a;
                            prev = a;
                            sumQty = a.getProducedQuantity();
                        }
                    }
                    // 收尾
                    if (start != null) {
                        String time = toTimeRange(start.getTimeSlot(), prev.getTimeSlot());
                        System.out.printf("%s,%s,%s,%d,%s%n",
                                line.getCode(), date, item.getCode(), sumQty, time);
                    }
                }
            }
        }
    }

    private static String toTimeRange(TimeSlot start, TimeSlot end) {
        int startHour = start.getHour();
        int endHourExclusive = end.getHour() + 1;
        return startHour + ":00-" + endHourExclusive + ":00";
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
}