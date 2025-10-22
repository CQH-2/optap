package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class ProductionPlannerApp {

    public static void main(String[] args) {
        // 1) 物料
        Item a = new Item(1L, "A", "总成A");
        Item b = new Item(2L, "B", "子件B");
        Item c = new Item(3L, "C", "子件C");

        // 配置安全库存示例（单位与任务数量一致）
        a.setSafetyStock(200);
        b.setSafetyStock(200);
        c.setSafetyStock(200);

        // 2) 工艺与物料支持关系
        Router rAsm = new Router(1L, "装配");
        Router rFab = new Router(2L, "加工");
        rAsm.getSupportedItems().add(a);   // A 用装配

        rFab.getSupportedItems().add(b);   // B 用加工
        rFab.getSupportedItems().add(c);   // C 用加工

        // 3) 产线与支持的工艺
        Line l1 = new Line(1L, "L1");
        l1.getSupportedRouters().add(rAsm);


        Line l2 = new Line(2L, "L2");
        l2.getSupportedRouters().add(rFab);

        List<Item> items = Arrays.asList(a, b, c);
        List<Router> routers = Arrays.asList(rAsm, rFab);
        List<Line> lines = Arrays.asList(l1, l2);

        // 4) 班段（slot）与产能（capacityUnits）→ 连续安排 10 天
        LocalDate startDay = LocalDate.now();
        int days = 10;
        List<LineShiftSlot> slots = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = startDay.plusDays(i);

            // L1：每天两个班段（与原有时间保持一致）
            // 注意：这里的 capacityUnits 仍沿用原值（5），如需“把槽位扩大一点”，可自行调大该值
            long l1Base = 1000 + (i * 10L);
            slots.add(new LineShiftSlot(l1Base + 1, l1, d, 8 * 60, 12 * 60, 5));
            slots.add(new LineShiftSlot(l1Base + 2, l1, d, 13 * 60, 17 * 60, 5));

            // L2：每天一个长班段
            long l2Base = 2000 + (i * 10L);
            slots.add(new LineShiftSlot(l2Base + 1, l2, d, 7 * 60, 15 * 60, 200));
        }

        // 5) BOM：A = 2*B + 3*C（子件需比父件早1天到位）
        List<BomComponent> bom = Arrays.asList(
                new BomComponent(1L, a, b, 2, 1),
                new BomComponent(2L, a, c, 3, 1)
        );

        // 6) 顶层需求：需要 200 个 A，交期设为第 10 天的次日（可按需调整）
        LocalDate due = startDay.plusDays(days); // 第10天后的次日
        List<Demand> demands = Collections.singletonList(
                new Demand(10001L, a, 200, due)
        );

        // 7) 将需求按 BOM 展开 → 生成待排产任务（Task），并赋交期
        List<Task> tasks = explodeDemandsToTasks(demands, bom);

        // 7.1 拆分 Task → TaskPart（允许跨班段排产）
        // 如需按产线能力自适应拆分，可改成你之前的 splitTasksByCapacity(...)
        List<TaskPart> parts = splitTasks(tasks, 50);

        // 8) 组装问题实例
        ProductionSchedule problem = new ProductionSchedule();
        problem.setItemList(items);
        problem.setRouterList(routers);
        problem.setLineList(lines);
        problem.setSlotList(slots);
        problem.setBomList(bom);
        problem.setDemandList(demands);
        problem.setTaskList(tasks);         // 作为问题事实
        problem.setTaskPartList(parts);     // 作为规划实体

        // 9) 构建并求解
        SolverFactory<ProductionSchedule> factory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        Solver<ProductionSchedule> solver = factory.buildSolver();

        ProductionSchedule best = solver.solve(problem);

        // 10) 中文输出：按 Task 汇总 + 分片明细
        System.out.println("最佳分数 = " + best.getScore());
        Map<Long, List<TaskPart>> byTask = best.getTaskPartList().stream()
                .collect(Collectors.groupingBy(tp -> tp.getTask().getId(), LinkedHashMap::new, Collectors.toList()));

        for (Task t : best.getTaskList()) {
            List<TaskPart> list = byTask.getOrDefault(t.getId(), Collections.emptyList());
            int sumQty = list.stream().mapToInt(TaskPart::getQuantity).sum();

            System.out.printf("任务 %d（物料=%s，数量=%d，交期=%s）→ 已拆分=%d 个分片，已分配总数=%d%n",
                    t.getId(),
                    t.getItem() != null ? t.getItem().getCode() : "未知物料",
                    t.getQuantity(),
                    t.getDueDate() != null ? t.getDueDate().toString() : "未设置",
                    list.size(),
                    sumQty
            );

            for (TaskPart p : list) {
                LineShiftSlot s = p.getSlot();
                String lineName = (s != null && s.getLine() != null) ? s.getLine().getName() : "未分配";
                String dateStr  = (s != null && s.getDate() != null) ? s.getDate().toString() : "未分配";
                String timeStr  = (s != null) ? (fmtMin(s.getStartMinuteOfDay()) + "-" + fmtMin(s.getEndMinuteOfDay())) : "未分配";
                String routerName = (p.getRouter() != null) ? p.getRouter().getName() : "未选择";
                System.out.printf("  分片(id=%d，数量=%d) → 产线=%s，日期=%s，时间=%s，工艺=%s%n",
                        p.getId(), p.getQuantity(), lineName, dateStr, timeStr, routerName
                );
            }
        }
    }

    // 需求展开（多层）：聚合每种物料的需求总量与最早交期
    private static List<Task> explodeDemandsToTasks(List<Demand> demands, List<BomComponent> bom) {
        Map<Item, List<BomComponent>> childrenByParent = new HashMap<>();
        for (BomComponent bc : bom) {
            childrenByParent.computeIfAbsent(bc.getParentItem(), k -> new ArrayList<>()).add(bc);
        }

        Map<Item, Integer> qtyByItem = new HashMap<>();
        Map<Item, LocalDate> dueByItem = new HashMap<>();

        Deque<Demand> queue = new ArrayDeque<>(demands);
        while (!queue.isEmpty()) {
            Demand cur = queue.pollFirst();
            Item item = cur.getItem();
            int qty = cur.getQuantity();
            LocalDate due = cur.getDueDate();

            qtyByItem.merge(item, qty, Integer::sum);
            dueByItem.merge(item, due, (oldDue, newDue) -> newDue.isBefore(oldDue) ? newDue : oldDue);

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

    // 简单拆分：按固定粒度切分，最后一片可能小于粒度
    private static List<TaskPart> splitTasks(List<Task> tasks, int chunkSize) {
        List<TaskPart> parts = new ArrayList<>();
        AtomicLong seq = new AtomicLong(1_000_000L);
        for (Task t : tasks) {
            int remain = t.getQuantity();
            while (remain > 0) {
                int take = Math.min(remain, chunkSize);
                parts.add(new TaskPart(seq.getAndIncrement(), t, take));
                remain -= take;
            }
        }
        return parts;
    }

    private static String fmtMin(int minuteOfDay) {
        int h = minuteOfDay / 60;
        int m = minuteOfDay % 60;
        return String.format("%02d:%02d", h, m);
    }
}