package com.iimsoft.scheduler;


import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    }

    private static void printSchedule(ProductionSchedule solution) {
        System.out.println("=== Schedule ===");
        var assignments = new ArrayList<>(solution.getAssignmentList());
        assignments.sort(Comparator
                .comparing((ProductionAssignment a) -> a.getTimeSlot().getIndex())
                .thenComparing(a -> a.getLine().getCode()));
        for (ProductionAssignment a : assignments) {
            String line = a.getLine().getCode();
            String time = a.getTimeSlot().toString();
            String router = a.getRouter() == null ? "IDLE" : a.getRouter().getCode();
            String item = a.getProducedItem() == null ? "-" : a.getProducedItem().getCode();
            int qty = a.getProducedQuantity();
            System.out.printf("%s | %s | %-8s | item=%-4s qty=%d%n", time, line, router, item, qty);
        }

        // 汇总产量
        Map<Item, Integer> producedByItem = assignments.stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(ProductionAssignment::getProducedItem,
                        Collectors.summingInt(ProductionAssignment::getProducedQuantity)));
        System.out.println("=== Production Summary ===");
        producedByItem.forEach((item, sum) ->
                System.out.println(item.getCode() + " -> " + sum));

        System.out.println("=== Demands ===");
        for (DemandOrder d : solution.getDemandList()) {
            int producedUpToDue = assignments.stream()
                    .filter(a -> a.getRouter() != null
                            && d.getItem().equals(a.getProducedItem())
                            && a.getTimeSlot().getIndex() <= d.getDueTimeSlotIndex())
                    .mapToInt(ProductionAssignment::getProducedQuantity)
                    .sum();
            System.out.printf("Demand %s x%d due %s[#%d], producedUpToDue=%d, shortage=%d%n",
                    d.getItem().getCode(), d.getQuantity(), d.getDueDate(), d.getDueTimeSlotIndex(),
                    producedUpToDue, Math.max(0, d.getQuantity() - producedUpToDue));
        }
    }

    // ====== 示例数据生成 ======
    static class ExampleData {
        List<Item> items;
        List<BomArc> bomArcs;
        List<Router> routers;
        List<ProductionLine> lines;
        List<TimeSlot> timeSlots;
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

        // 时间槽：2天，每天班次 8-19（12小时）
        LocalDate day1 = LocalDate.now();
        LocalDate day2 = day1.plusDays(1);
        List<TimeSlot> slots = new ArrayList<>();
        int index = 0;
        for (LocalDate d0 : List.of(day1, day2)) {
            for (int h = 8; h <= 19; h++) { // 8..19 共12小时
                slots.add(new TimeSlot(d0, h, index++));
            }
        }
        d.timeSlots = slots;

        // 需求：A 50件，截止到第1天最后一个班次小时
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