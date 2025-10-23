package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.util.HourSlotGenerator;
import com.iimsoft.scheduler.util.WorkingHours;
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
        a.setSafetyStock(200);
        b.setSafetyStock(200);
        c.setSafetyStock(200);

        // 2) 工艺与物料支持关系
        Router rAsm = new Router(1L, "装配");
        Router rFab = new Router(2L, "加工");
        rAsm.getSupportedItems().add(a);   // A 用装配
        rFab.getSupportedItems().add(b);   // B 用加工
        rFab.getSupportedItems().add(c);   // C 用加工

        // 3) 产线与速率（按“件/小时”配置，更直观）
        Line l1 = new Line(1L, "L1");
        l1.getSupportedRouters().add(rAsm);
        l1.putUnitsPerHour(rAsm, 30); // 每小时 30 件

        Line l2 = new Line(2L, "L2");
        l2.getSupportedRouters().add(rFab);
        l2.putUnitsPerHour(rFab, 60); // 每小时 60 件

        List<Item> items = Arrays.asList(a, b, c);
        List<Router> routers = Arrays.asList(rAsm, rFab);
        List<Line> lines = Arrays.asList(l1, l2);

        // 4) BOM：A = 2*B + 3*C（子件需比父件早1天到位）
        List<BomComponent> bom = Arrays.asList(
                new BomComponent(1L, a, b, 2, 1),
                new BomComponent(2L, a, c, 3, 1)
        );

        // 5) 顶层需求（示例）
        LocalDate today = LocalDate.now();
        LocalDate due = today.plusDays(2);
        List<Demand> demands = Collections.singletonList(new Demand(10001L, a, 200, due));

        // 6) 将需求按 BOM 展开 → Requirement 列表
        List<Requirement> requirements = explodeDemandsToRequirements(demands, bom);

        // 7) “倒推固定2天”的小时槽位：以最大交期为结束，窗口仅覆盖2天
        WorkingHours workingHours = new WorkingHours(8, 17); // 工作时段 8:00-17:00
        long baseId = 10_000L;                               // 槽位起始ID
        List<LineHourSlot> hourSlots = HourSlotGenerator.buildBackwardSlotsForDays(
                lines, requirements, workingHours, 2, baseId
        );

        // 8) 为每个小时槽位创建一个 HourPlan（变量 item、quantity 由求解器决定）
        List<HourPlan> plans = new ArrayList<>();
        AtomicLong planId = new AtomicLong(1_000_000L);
        for (LineHourSlot s : hourSlots) {
            plans.add(new HourPlan(planId.getAndIncrement(), s));
        }

        // 9) 组装问题实例
        ProductionSchedule problem = new ProductionSchedule();
        problem.setItemList(items);
        problem.setRouterList(routers);
        problem.setLineList(lines);
        problem.setHourSlotList(hourSlots);
        problem.setBomList(bom);
        problem.setDemandList(demands);
        problem.setRequirementList(requirements);
        problem.setPlanList(plans);

        // 与件/小时能力相匹配的数量上限（不必太大，缩小搜索空间）
        problem.setMaxQuantityPerHour(60);

        // 10) 求解
        SolverFactory<ProductionSchedule> factory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        Solver<ProductionSchedule> solver = factory.buildSolver();
        ProductionSchedule best = solver.solve(problem);

        // 11) 输出
        System.out.println("最佳分数 = " + best.getScore());

// 按生产线分组展示
        best.getLineList().forEach(line -> {
            System.out.println("\n生产线：" + line.getName());
            best.getPlanList().stream()
                    .filter(p -> p.getLine().equals(line))
                    .sorted(Comparator.<HourPlan, LocalDate>comparing(p -> p.getSlot().getDate())
                            .thenComparingInt(p -> p.getSlot().getHourOfDay()))
                    .forEach(p -> {
                        String dateStr = p.getSlot().getDate().toString();
                        int h = p.getSlot().getHourOfDay();
                        int qty = p.getQuantity() == null ? 0 : p.getQuantity();
                        String itemCode = (qty > 0 && p.getItem() != null) ? p.getItem().getCode() : "-";
                        System.out.printf("%s %02d:00 - item=%s, qty=%d%n", dateStr, h, itemCode, qty);
                    });
        });

// 汇总对比：按物料累计总量
        Map<Item, Integer> producedByItem = best.getPlanList().stream()
                .filter(p -> p.getItem() != null && p.getQuantity() != null && p.getQuantity() > 0)
                .collect(Collectors.groupingBy(HourPlan::getItem, Collectors.summingInt(HourPlan::getQuantity)));

        System.out.println("\n产出汇总（按物料）");
        for (Requirement r : best.getRequirementList()) {
            int produced = producedByItem.getOrDefault(r.getItem(), 0);
            System.out.printf("Item %s 需求=%d 产出=%d 交期=%s%n",
                    r.getItem().getCode(), r.getQuantity(), produced, r.getDueDate());
        }
    }

    // 需求展开（多层）：聚合每种物料的需求总量与最早交期
    private static List<Requirement> explodeDemandsToRequirements(List<Demand> demands, List<BomComponent> bom) {
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

        List<Requirement> reqs = new ArrayList<>();
        long seq = 1L;
        for (Map.Entry<Item, Integer> e : qtyByItem.entrySet()) {
            Item item = e.getKey();
            int q = e.getValue();
            LocalDate due = dueByItem.get(item);
            reqs.add(new Requirement(seq++, item, q, due));
        }
        return reqs;
    }
}