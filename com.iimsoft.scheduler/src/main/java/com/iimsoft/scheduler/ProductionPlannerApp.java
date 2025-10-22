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
        // 1) 物料（3级：A <- B <- C）
        Item A = new Item(1L, "A", "总成A");
        Item B = new Item(2L, "B", "中间件B");
        Item C = new Item(3L, "C", "原料C");

        // 2) 工艺：每个物料独占一条工艺
        Router rA = new Router(11L, "装配A");
        Router rB = new Router(12L, "加工B");
        Router rC = new Router(13L, "冶炼C");
        rA.getSupportedItems().add(A);
        rB.getSupportedItems().add(B);
        rC.getSupportedItems().add(C);

        // 3) 产线：每个物料独占一条产线 + 必须配置节拍（分钟/件）
        Line lineA = new Line(21L, "Line-A");
        Line lineB = new Line(22L, "Line-B");
        Line lineC = new Line(23L, "Line-C");

        lineA.getSupportedRouters().add(rA);
        lineB.getSupportedRouters().add(rB);
        lineC.getSupportedRouters().add(rC);

        // 节拍（分钟/件）——按 12h 日班产能与 10h 夜班产能折算的合理示例
        // 日班 12h = 720 分钟；夜班 10h = 600 分钟
        // A：6 分/件 → 日班 120 件，夜班 100 件
        // B：3 分/件 → 日班 240 件，夜班 200 件
        // C：2 分/件 → 日班 360 件，夜班 300 件
        lineA.getMinutesPerUnitByRouter().put(rA, 6);
        lineB.getMinutesPerUnitByRouter().put(rB, 3);
        lineC.getMinutesPerUnitByRouter().put(rC, 2);

        List<Item> items = Arrays.asList(A, B, C);
        List<Router> routers = Arrays.asList(rA, rB, rC);
        List<Line> lines = Arrays.asList(lineA, lineB, lineC);

        // 4) 班段（slot）：每天两班（08:00–20:00，21:00–次日07:00）
        // 夜班跨天，用两个槽位表达：当日 21:00–24:00 + 次日 00:00–07:00
        LocalDate d1 = LocalDate.now();
        LocalDate d2 = d1.plusDays(1);

        List<LineShiftSlot> slots = new ArrayList<>();
        // Line-A（A 独占）
        addTwoShiftsForDay(slots, lineA, d1,
                /*dayCapacity*/120, /*nightCapacity*/100);
        addTwoShiftsForDay(slots, lineA, d2,
                120, 100);
        // Line-B（B 独占）
        addTwoShiftsForDay(slots, lineB, d1,
                240, 200);
        addTwoShiftsForDay(slots, lineB, d2,
                240, 200);
        // Line-C（C 独占）
        addTwoShiftsForDay(slots, lineC, d1,
                360, 300);
        addTwoShiftsForDay(slots, lineC, d2,
                360, 300);

        // 5) BOM（3级：A = 1*B，B = 1*C），子件提前期：B 比 A 早 1 天，C 比 B 早 1 天
        List<BomComponent> bom = Arrays.asList(
                new BomComponent(101L, A, B, 1, 1),
                new BomComponent(102L, B, C, 1, 1)
        );

        // 6) 顶层需求：例如需要 200 个 A，交期为 d2（次日）
        List<Demand> demands = Collections.singletonList(
                new Demand(201L, A, 200, d2)
        );

        // 7) 由 BOM 展开 → 生成 Task（A、B、C 都会有任务，B/C 交期更早）
        List<Task> tasks = explodeDemandsToTasks(demands, bom);

        // 8) 按“产线/工艺/槽位能力”自适应拆分（单片不超过任意可行槽位能力）
        List<TaskPart> parts = splitTasksByCapacity(tasks, routers, slots);

        // 9) 组装问题实例
        ProductionSchedule problem = new ProductionSchedule();
        problem.setItemList(items);
        problem.setRouterList(routers);
        problem.setLineList(lines);
        problem.setSlotList(slots);
        problem.setBomList(bom);
        problem.setDemandList(demands);
        problem.setTaskList(tasks);
        problem.setTaskPartList(parts);

        // 10) 求解
        SolverFactory<ProductionSchedule> factory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        Solver<ProductionSchedule> solver = factory.buildSolver();
        ProductionSchedule best = solver.solve(problem);

        // 11) 输出
        System.out.println("最佳分数 = " + best.getScore());
        Map<Long, List<TaskPart>> byTask = best.getTaskPartList().stream()
                .collect(Collectors.groupingBy(tp -> tp.getTask().getId(), LinkedHashMap::new, Collectors.toList()));

        for (Task t : best.getTaskList()) {
            List<TaskPart> list = byTask.getOrDefault(t.getId(), Collections.emptyList());
            int sumQty = list.stream().mapToInt(TaskPart::getQuantity).sum();

            System.out.printf("任务 %d（物料=%s，数量=%d，交期=%s）→ 分片=%d，总分配=%d%n",
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
                System.out.printf("  分片(id=%d，qty=%d) → 产线=%s，日期=%s，时间=%s，工艺=%s%n",
                        p.getId(), p.getQuantity(), lineName, dateStr, timeStr, routerName
                );
            }
        }
    }

    // 为某天预生成两个班（08:00–20:00，夜班 21:00–次日07:00），并为每个班设置 capacityUnits
    private static void addTwoShiftsForDay(List<LineShiftSlot> slots, Line line, LocalDate day,
                                           int dayCapacityUnits, int nightCapacityUnits) {
        long baseId = (line.getId() * 10_000) + (day.toEpochDay() % 10_000);

        // 日班：08:00–20:00 → 12 小时
        slots.add(new LineShiftSlot(baseId + 1, line, day, 8 * 60, 20 * 60, dayCapacityUnits));

        // 夜班拆两段表达（总 10 小时 = 600 分钟）
        // 21:00–24:00（3 小时，占夜班产能 3/10）
        int nightPart1 = (int) Math.floor(nightCapacityUnits * (3.0 / 10.0));
        // 次日 00:00–07:00（7 小时，占夜班产能 7/10）
        int nightPart2 = nightCapacityUnits - nightPart1;

        slots.add(new LineShiftSlot(baseId + 2, line, day, 21 * 60, 24 * 60, nightPart1));
        slots.add(new LineShiftSlot(baseId + 3, line, day.plusDays(1), 0, 7 * 60, nightPart2));
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

    // 按“产线/工艺/槽位能力”自适应拆分（单片不超过任意可行槽位的能力）
    private static List<TaskPart> splitTasksByCapacity(List<Task> tasks, List<Router> routers, List<LineShiftSlot> slots) {
        List<TaskPart> parts = new ArrayList<>();
        AtomicLong seq = new AtomicLong(1_000_000L);

        for (Task t : tasks) {
            int safeChunk = computeSafeChunkForItem(t.getItem(), routers, slots);
            if (safeChunk <= 0) {
                // 没有任何可行槽位（产线/工艺组合不支持该物料），保底拆为 1，避免死循环；也可以直接抛错
                safeChunk = 1;
            }
            int remain = t.getQuantity();
            while (remain > 0) {
                int take = Math.min(remain, safeChunk);
                parts.add(new TaskPart(seq.getAndIncrement(), t, take));
                remain -= take;
            }
        }
        return parts;
    }

    // 计算某物料的“安全粒度”（单片最大件数）：遍历所有可行（line×router）和其槽位，取每个槽位能力的最小值
    // 此处使用 slot.capacityUnits（件数口径），因为当前 DRL 的容量硬约束基于 capacityUnits。
    private static int computeSafeChunkForItem(Item item, List<Router> routers, List<LineShiftSlot> slots) {
        int safe = Integer.MAX_VALUE;

        for (LineShiftSlot s : slots) {
            Line line = s.getLine();
            for (Router r : routers) {
                boolean feasible = r.supports(item) && line.getSupportedRouters().contains(r)
                        && line.getMinutesPerUnitByRouter().containsKey(r); // 与 Line.supports(router) 保持一致
                if (!feasible) continue;

                int byUnits = s.getCapacityUnits() > 0 ? s.getCapacityUnits() : Integer.MAX_VALUE;
                int slotCap = byUnits;
                if (slotCap < safe) {
                    safe = slotCap;
                }
            }
        }
        if (safe == Integer.MAX_VALUE) {
            return 1;
        }
        return Math.max(1, safe);
    }

    private static String fmtMin(int minuteOfDay) {
        int h = minuteOfDay / 60;
        int m = minuteOfDay % 60;
        return String.format("%02d:%02d", h, m);
    }
}