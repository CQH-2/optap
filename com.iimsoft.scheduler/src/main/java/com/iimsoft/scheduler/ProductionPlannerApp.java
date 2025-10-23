package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.ga.GeneticAlgorithmScheduler;
import com.iimsoft.scheduler.util.HourSlotGenerator;
import com.iimsoft.scheduler.util.WorkingHours;

import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.impl.score.director.ScoreDirectorFactory;
import org.optaplanner.core.impl.score.director.ScoreDirectorFactoryFactory;

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
        rAsm.getSupportedItems().add(a);
        rFab.getSupportedItems().add(b);
        rFab.getSupportedItems().add(c);

        // 3) 产线与速率（件/小时）
        Line l1 = new Line(1L, "L1");
        l1.getSupportedRouters().add(rAsm);
        l1.putUnitsPerHour(rAsm, 30);

        Line l2 = new Line(2L, "L2");
        l2.getSupportedRouters().add(rFab);
        l2.putUnitsPerHour(rFab, 60);

        List<Item> items = Arrays.asList(a, b, c);
        List<Router> routers = Arrays.asList(rAsm, rFab);
        List<Line> lines = Arrays.asList(l1, l2);

        // 4) BOM
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

        // 7) “倒推固定2天”的小时槽位
        WorkingHours workingHours = new WorkingHours(8, 17);
        long baseId = 10_000L;
        List<LineHourSlot> hourSlots = HourSlotGenerator.buildBackwardSlotsForDays(
                lines, requirements, workingHours, 2, baseId
        );

        // 8) 为每个小时槽位创建一个 HourPlan（变量 item、quantity）
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
        problem.setMaxQuantityPerHour(60);

        // 10) 构建 ScoreDirectorFactory（从 solverConfig.xml 读取 DRL 配置）
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solverConfig.xml");
        ScoreDirectorFactoryConfig scoreDirConfig = solverConfig.getScoreDirectorFactoryConfig();
        if (scoreDirConfig == null) {
            scoreDirConfig = new ScoreDirectorFactoryConfig();
            scoreDirConfig.setScoreDrlList(List.of("rules/productionConstraints.drl"));
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        ScoreDirectorFactory<ProductionSchedule> scoreDirectorFactory =
                new ScoreDirectorFactoryFactory<ProductionSchedule>(cl, scoreDirConfig).buildScoreDirectorFactory();

        // 11) 运行遗传算法（默认），可通过系统属性切回原本解器：-Duse.optaplanner=true
        boolean useOptaPlannerSolver = Boolean.getBoolean("use.optaplanner");
        ProductionSchedule best;
        if (useOptaPlannerSolver) {
            SolverFactory<ProductionSchedule> solverFactory =
                    SolverFactory.createFromXmlResource("solverConfig.xml");
            Solver<ProductionSchedule> solver = solverFactory.buildSolver();
            best = solver.solve(problem);
        } else {
            GeneticAlgorithmScheduler.GAParams ga = new GeneticAlgorithmScheduler.GAParams();
            ga.populationSize = 160;
            ga.generations = 500;
            ga.crossoverRate = 0.9;
            ga.mutationRate = 0.025;
            ga.tournamentSize = 5;
            ga.eliteCount = 2;
            ga.parallelEvaluation = true;
            ga.randomSeed = 0L;

            GeneticAlgorithmScheduler scheduler = new GeneticAlgorithmScheduler(scoreDirectorFactory, ga.randomSeed);
            long t0 = System.currentTimeMillis();
            best = scheduler.solve(problem, ga);
            long t1 = System.currentTimeMillis();
            System.out.println("GA solve time = " + (t1 - t0) + " ms");
        }

        // 12) 输出
        System.out.println("最佳分数 = " + best.getScore());

        ChildInvSnapshots snapshots = buildChildInventorySnapshots(best);

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
                        boolean hasProd = (qty > 0 && p.getItem() != null);
                        String itemCode = hasProd ? p.getItem().getCode() : "-";

                        String childInvStr = "-";
                        if (hasProd) {
                            Map<Item, Integer> snap = snapshots.perPlanChildInv.getOrDefault(p.getId(), Collections.emptyMap());
                            childInvStr = formatChildInventory(snap, snapshots.childItemsInBom);
                        }

                        System.out.printf("%s %02d:00 - item=%s, qty=%d, childInv=%s%n",
                                dateStr, h, itemCode, qty, childInvStr);
                    });
        });

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

    private static class ChildInvSnapshots {
        final Map<Long, Map<Item, Integer>> perPlanChildInv;
        final List<Item> childItemsInBom;
        ChildInvSnapshots(Map<Long, Map<Item, Integer>> perPlanChildInv, List<Item> childItemsInBom) {
            this.perPlanChildInv = perPlanChildInv;
            this.childItemsInBom = childItemsInBom;
        }
    }

    private static ChildInvSnapshots buildChildInventorySnapshots(ProductionSchedule sol) {
        List<BomComponent> bomList = sol.getBomList() == null ? Collections.emptyList() : sol.getBomList();

        LinkedHashSet<Item> childSet = new LinkedHashSet<>();
        for (BomComponent bc : bomList) {
            if (bc != null && bc.getChildItem() != null) {
                childSet.add(bc.getChildItem());
            }
        }
        List<Item> childItems = new ArrayList<>(childSet);

        Map<Item, List<BomComponent>> childrenByParent = new HashMap<>();
        for (BomComponent bc : bomList) {
            if (bc == null || bc.getParentItem() == null) continue;
            childrenByParent.computeIfAbsent(bc.getParentItem(), k -> new ArrayList<>()).add(bc);
        }

        Map<Item, Integer> available = new HashMap<>();
        for (Item child : childItems) {
            available.put(child, 0);
        }

        Map<Long, List<HourPlan>> plansByStart = sol.getPlanList().stream()
                .collect(Collectors.groupingBy(HourPlan::getStartIndex));

        List<Long> times = new ArrayList<>(plansByStart.keySet());
        Collections.sort(times);

        Map<Long, Map<Item, Integer>> result = new HashMap<>();

        for (Long t : times) {
            List<HourPlan> group = plansByStart.getOrDefault(t, Collections.emptyList());

            for (HourPlan p : group) {
                int q = (p.getQuantity() == null) ? 0 : p.getQuantity();
                if (p.getItem() == null || q <= 0) continue;
                Map<Item, Integer> snap = new LinkedHashMap<>();
                for (Item child : childItems) {
                    snap.put(child, available.getOrDefault(child, 0));
                }
                result.put(p.getId(), snap);
            }

            for (HourPlan p : group) {
                int q = (p.getQuantity() == null) ? 0 : p.getQuantity();
                Item it = p.getItem();
                if (it == null || q <= 0) continue;
                if (childSet.contains(it)) {
                    available.merge(it, q, Integer::sum);
                }
            }

            for (HourPlan p : group) {
                int q = (p.getQuantity() == null) ? 0 : p.getQuantity();
                Item parent = p.getItem();
                if (parent == null || q <= 0) continue;
                List<BomComponent> children = childrenByParent.get(parent);
                if (children == null) continue;
                for (BomComponent bc : children) {
                    Item child = bc.getChildItem();
                    int consume = q * Math.max(0, bc.getQuantityPer());
                    if (consume != 0) {
                        available.merge(child, -consume, Integer::sum);
                    }
                }
            }
        }

        return new ChildInvSnapshots(result, childItems);
    }

    private static String formatChildInventory(Map<Item, Integer> snap, List<Item> order) {
        if (snap == null || snap.isEmpty() || order == null || order.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Item it : order) {
            if (!first) sb.append(", ");
            first = false;
            int v = snap.getOrDefault(it, 0);
            sb.append(it.getCode()).append("=").append(v);
        }
        sb.append("}");
        return sb.toString();
    }
}