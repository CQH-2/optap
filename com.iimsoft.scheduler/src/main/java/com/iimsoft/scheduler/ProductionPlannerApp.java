package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;

import java.time.LocalDate;
import java.util.*;

public class ProductionPlannerApp {

    public static void main(String[] args) {
        // 1) 物料
        Item a = new Item(1L, "A", "总成A");
        Item b = new Item(2L, "B", "子件B");
        Item c = new Item(3L, "C", "子件C");

        // 2) 工艺与物料支持关系
        Router rAsm = new Router(1L, "装配");
        Router rFab = new Router(2L, "加工");
        rAsm.getSupportedItems().add(a);   // A 用装配
        rFab.getSupportedItems().add(b);   // B 用加工
        rFab.getSupportedItems().add(c);   // C 用加工

        // 3) 产线与支持的工艺
        Line l1 = new Line(1L, "L1");
        l1.getSupportedRouters().add(rAsm);
        l1.getSupportedRouters().add(rFab);

        Line l2 = new Line(2L, "L2");
        l2.getSupportedRouters().add(rFab);

        List<Item> items = Arrays.asList(a, b, c);
        List<Router> routers = Arrays.asList(rAsm, rFab);
        List<Line> lines = Arrays.asList(l1, l2);

        // 4) 班段（slot）与产能（capacityUnits）
        LocalDate d1 = LocalDate.now();
        LocalDate d2 = d1.plusDays(1);
        List<LineShiftSlot> slots = new ArrayList<>();
        // L1：两天各两个班段
        slots.add(new LineShiftSlot(1001L, l1, d1, 8 * 60, 12 * 60, 120));
        slots.add(new LineShiftSlot(1002L, l1, d1, 13 * 60, 17 * 60, 100));
        slots.add(new LineShiftSlot(1003L, l1, d2, 8 * 60, 12 * 60, 120));
        slots.add(new LineShiftSlot(1004L, l1, d2, 13 * 60, 17 * 60, 100));
        // L2：两天各一个长班段
        slots.add(new LineShiftSlot(2001L, l2, d1, 7 * 60, 15 * 60, 200));
        slots.add(new LineShiftSlot(2002L, l2, d2, 7 * 60, 15 * 60, 200));

        // 5) BOM：A = 2*B + 3*C（子件需比父件早1天到位）
        List<BomComponent> bom = Arrays.asList(
                new BomComponent(1L, a, b, 2, 1),
                new BomComponent(2L, a, c, 3, 1)
        );

        // 6) 顶层需求：需要 100 个 A，交期为 d2（次日）
        List<Demand> demands = Collections.singletonList(
                new Demand(10001L, a, 100, d2)
        );

        // 7) 将需求按 BOM 展开 → 生成待排产任务（Task），并赋交期
        List<Task> tasks = explodeDemandsToTasks(demands, bom);

        // 8) 组装问题实例
        ProductionSchedule problem = new ProductionSchedule();
        problem.setItemList(items);
        problem.setRouterList(routers);
        problem.setLineList(lines);
        problem.setSlotList(slots);
        problem.setBomList(bom);
        problem.setDemandList(demands);
        problem.setTaskList(tasks);

        // 9) 构建并求解
        SolverFactory<ProductionSchedule> factory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        Solver<ProductionSchedule> solver = factory.buildSolver();

        ProductionSchedule best = solver.solve(problem);

        // 10) 中文输出（按任务）
        System.out.println("最佳分数 = " + best.getScore());
        for (Task t : best.getTaskList()) {
            LineShiftSlot s = t.getSlot();
            String lineName = s != null && s.getLine() != null ? s.getLine().getName() : "未分配";
            String dateStr  = s != null && s.getDate() != null ? s.getDate().toString() : "未分配";
            String timeStr  = s != null ? (fmtMin(s.getStartMinuteOfDay()) + "-" + fmtMin(s.getEndMinuteOfDay())) : "未分配";
            String routerName = t.getRouter() != null ? t.getRouter().getName() : "未选择";
            String dueStr = t.getDueDate() != null ? t.getDueDate().toString() : "未设置";
            System.out.printf("任务 %d（物料=%s，数量=%d，交期=%s）→ 产线=%s，日期=%s，时间=%s，工艺=%s%n",
                    t.getId(),
                    t.getItem() != null ? t.getItem().getCode() : "未知物料",
                    t.getQuantity(),
                    dueStr,
                    lineName, dateStr, timeStr, routerName
            );
        }
    }

    // 需求展开（最小实现）：将顶层需求按 BOM 多层展开，
    // 聚合为每个物料的一条 Task；交期取“最早到达”的交期（即最小日期）。
    private static List<Task> explodeDemandsToTasks(List<Demand> demands, List<BomComponent> bom) {
        // 建立父->子 的索引
        Map<Item, List<BomComponent>> childrenByParent = new HashMap<>();
        for (BomComponent bc : bom) {
            childrenByParent.computeIfAbsent(bc.getParentItem(), k -> new ArrayList<>()).add(bc);
        }

        // 累加每个物料的总量与最早交期
        Map<Item, Integer> qtyByItem = new HashMap<>();
        Map<Item, LocalDate> dueByItem = new HashMap<>();

        // 队列迭代展开
        Deque<Demand> queue = new ArrayDeque<>(demands);
        while (!queue.isEmpty()) {
            Demand cur = queue.pollFirst();
            Item item = cur.getItem();
            int qty = cur.getQuantity();
            LocalDate due = cur.getDueDate();

            // 聚合当前物料
            qtyByItem.merge(item, qty, Integer::sum);
            dueByItem.merge(item, due, (oldDue, newDue) -> newDue.isBefore(oldDue) ? newDue : oldDue);

            // 展开子件
            List<BomComponent> children = childrenByParent.get(item);
            if (children != null) {
                for (BomComponent bc : children) {
                    Item child = bc.getChildItem();
                    int childQty = qty * bc.getQuantityPer();
                    LocalDate childDue = due.minusDays(bc.getLeadTimeDays());
                    queue.addLast(new Demand(-1L, child, childQty, childDue));
                }
            }
        }

        // 生成 Task（带交期）
        List<Task> tasks = new ArrayList<>();
        long seq = 1000L;
        for (Map.Entry<Item, Integer> e : qtyByItem.entrySet()) {
            Item item = e.getKey();
            int q = e.getValue();
            LocalDate due = dueByItem.get(item);
            tasks.add(new Task(seq++, item, q, due));
        }
        return tasks;
    }

    private static String fmtMin(int minuteOfDay) {
        int h = minuteOfDay / 60;
        int m = minuteOfDay % 60;
        return String.format("%02d:%02d", h, m);
    }
}