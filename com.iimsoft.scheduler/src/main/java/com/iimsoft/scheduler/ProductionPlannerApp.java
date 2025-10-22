package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProductionPlannerApp {

    public static void main(String[] args) {
        // 1) 准备问题事实与任务（示例数据，按需替换为真实数据）

        // 物料
        Item i1 = new Item(1L, "ITEM-A", "物料A");
        Item i2 = new Item(2L, "ITEM-B", "物料B");

        // 工艺与物料支持关系
        Router r1 = new Router(1L, "工艺-1");
        Router r2 = new Router(2L, "工艺-2");
        r1.getSupportedItems().add(i1);
        r2.getSupportedItems().add(i1);
        r2.getSupportedItems().add(i2);

        // 产线与支持的工艺
        Line l1 = new Line(1L, "L1");
        l1.getSupportedRouters().add(r1);
        l1.getSupportedRouters().add(r2);

        Line l2 = new Line(2L, "L2");
        l2.getSupportedRouters().add(r2);

        List<Item> items = Arrays.asList(i1, i2);
        List<Router> routers = Arrays.asList(r1, r2);
        List<Line> lines = Arrays.asList(l1, l2);

        // 每条产线有自己的日期/时间段槽位（LineShiftSlot）
        LocalDate d = LocalDate.now(); // 示例：今天。可根据需要替换为具体计划日期

        List<LineShiftSlot> slots = new ArrayList<>();
        // L1：08:00-12:00 cap=120，13:00-17:00 cap=100
        slots.add(new LineShiftSlot(1001L, l1, d, 8 * 60, 12 * 60, 120));
        slots.add(new LineShiftSlot(1002L, l1, d, 13 * 60, 17 * 60, 100));
        // L2：07:00-15:00 cap=200（单一大班段）
        slots.add(new LineShiftSlot(2001L, l2, d, 7 * 60, 15 * 60, 200));

        // 工单任务：指定物料与数量（由求解器分配到具体槽位与工艺）
        Task t1 = new Task(101L, i1, 60);
        Task t2 = new Task(102L, i1, 50);
        Task t3 = new Task(103L, i2, 70);
        Task t4 = new Task(104L, i2, 40);
        List<Task> tasks = Arrays.asList(t1, t2, t3, t4);

        // 装配规划解
        ProductionSchedule problem = new ProductionSchedule();
        problem.setItemList(items);
        problem.setRouterList(routers);
        problem.setLineList(lines);
        problem.setSlotList(slots);
        problem.setTaskList(tasks);

        // 2) 构建并求解（solverConfig.xml 需指向基于 Slot 的 DRL 规则）
        SolverFactory<ProductionSchedule> factory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        Solver<ProductionSchedule> solver = factory.buildSolver();

        ProductionSchedule best = solver.solve(problem);

        // 3) 仅输出最终结果
        System.out.println("Best score = " + best.getScore());
        for (Task t : best.getTaskList()) {
            LineShiftSlot s = t.getSlot();
            String lineName = s != null && s.getLine() != null ? s.getLine().getName() : "null";
            String dateStr = s != null && s.getDate() != null ? s.getDate().toString() : "null";
            String timeStr = s != null ? (fmtMin(s.getStartMinuteOfDay()) + "-" + fmtMin(s.getEndMinuteOfDay())) : "null";
            String routerName = t.getRouter() != null ? t.getRouter().getName() : "null";
            System.out.printf("Task %d (%s x %d) -> Line=%s, Date=%s, Time=%s, Router=%s%n",
                    t.getId(),
                    t.getItem() != null ? t.getItem().getCode() : "null",
                    t.getQuantity(),
                    lineName, dateStr, timeStr, routerName);
        }
    }

    private static String fmtMin(int minuteOfDay) {
        int h = minuteOfDay / 60;
        int m = minuteOfDay % 60;
        return String.format("%02d:%02d", h, m);
    }
}