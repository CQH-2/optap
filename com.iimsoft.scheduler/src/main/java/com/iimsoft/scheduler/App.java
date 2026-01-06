package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.service.DataBuildService;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class App {

    public static void main(String[] args) throws Exception {
        // 1. 指定资源名（默认用复杂示例）
        String jsonFileName = "example_data_complex.json";
        // 2. 获取resources下的文件路径
        ClassLoader classLoader = App.class.getClassLoader();
        String jsonFilePath = Objects.requireNonNull(classLoader.getResource(jsonFileName)).getPath();

        // 3. 用DataBuildService加载（带智能班次规划）
        DataBuildService dataBuildService = new DataBuildService();
        
        // 指定生产开始日期
        LocalDate productionStartDate = LocalDate.of(2025, 10, 27);
        
        // 使用智能班次规划（自动判断是否需要夜班）
        ProductionSchedule problem = dataBuildService.buildScheduleWithShiftPlanning(
            jsonFilePath, productionStartDate);

        SolverFactory<ProductionSchedule> solverFactory =
                SolverFactory.createFromXmlResource("solverConfig.xml");
        var solverManager = SolverManager.create(solverFactory);
        long problemId = 1L;

        ProductionSchedule solution = solverManager.solve(problemId, problem).getFinalBestSolution();

        System.out.println("Score: " + solution.getScore());

        printSchedule(solution);
    }

    private static void printSchedule(ProductionSchedule solution) {
        System.out.println("==== 物料需求 ====");
        for (DemandOrder d : solution.getDemandList()) {
            System.out.printf("需求：物料%s，数量%d，截止日期%s，优先级%d%n",
                    d.getItem().getCode(), d.getQuantity(), d.getDueDate(), d.getPriority());
        }

        System.out.println("\n==== 班次使用统计 ====");
        Map<Shift, Integer> shiftUsage = new HashMap<>();
        for (ProductionAssignment a : solution.getAssignmentList()) {
            if (a.getRouter() != null) {
                Shift shift = a.getTimeSlot().getShift();
                shiftUsage.merge(shift, a.getProducedQuantity(), Integer::sum);
            }
        }
        for (Map.Entry<Shift, Integer> entry : shiftUsage.entrySet()) {
            System.out.printf("%s产量：%d 件%n", entry.getKey().getDisplayName(), entry.getValue());
        }

        // -------- 合并输出：按日期+生产线分组 --------
        System.out.println("\n==== 生产计划（按日期和生产线分开）====");
        
        // 按时间排序所有任务
        List<ProductionAssignment> sortedAssignments = solution.getAssignmentList().stream()
                .filter(a -> a.getRouter() != null && a.getProducedQuantity() > 0)
                .sorted(Comparator.comparing(a -> a.getTimeSlot().getIndex()))
                .toList();

        // 按日期分组
        Map<LocalDate, List<ProductionAssignment>> assignmentsByDate = sortedAssignments.stream()
                .collect(Collectors.groupingBy(a -> a.getTimeSlot().getDate()));

        // 按日期顺序输出
        assignmentsByDate.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(dateEntry -> {
                    LocalDate date = dateEntry.getKey();
                    List<ProductionAssignment> dailyAssignments = dateEntry.getValue();

                    System.out.printf("\n【 %s 】%n", date);

                    // 按生产线分组
                    Map<ProductionLine, List<ProductionAssignment>> assignmentsByLine = dailyAssignments.stream()
                            .collect(Collectors.groupingBy(ProductionAssignment::getLine));

                    // 按生产线的原始顺序输出
                    solution.getLineList().stream()
                            .filter(assignmentsByLine::containsKey)
                            .forEach(line -> {
                                List<ProductionAssignment> lineAssignments = assignmentsByLine.get(line);

                                System.out.printf("  ├─ %s%n", line.getCode());

                                // 合并连续的同一 (生产线、物料、工艺) 的任务
                                List<ProductionBatch> batches = new ArrayList<>();
                                ProductionBatch currentBatch = null;

                                for (ProductionAssignment a : lineAssignments) {
                                    if (currentBatch == null) {
                                        currentBatch = new ProductionBatch(a);
                                    } else if (currentBatch.canMerge(a)) {
                                        currentBatch.merge(a);
                                    } else {
                                        batches.add(currentBatch);
                                        currentBatch = new ProductionBatch(a);
                                    }
                                }
                                if (currentBatch != null) {
                                    batches.add(currentBatch);
                                }

                                // 输出该生产线的每一个批次
                                for (int i = 0; i < batches.size(); i++) {
                                    ProductionBatch batch = batches.get(i);
                                    boolean isLast = (i == batches.size() - 1);
                                    String prefix = isLast ? "  │   └─ " : "  │   ├─ ";
                                    System.out.printf("%s%s | %s | %d件 | %s%n",
                                            prefix,
                                            batch.getTimeRange(),
                                            batch.getItemName(),
                                            batch.getTotalQuantity(),
                                            batch.getRouterName());
                                }
                            });
                });

        // 汇总产量
        System.out.println("\n==== 产量汇总 ====");
        Map<Item, Integer> producedByItem = solution.getAssignmentList().stream()
                .filter(a -> a.getRouter() != null)
                .collect(Collectors.groupingBy(ProductionAssignment::getProducedItem,
                        Collectors.summingInt(ProductionAssignment::getProducedQuantity)));
        producedByItem.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getCode()))
                .forEach(e -> System.out.printf("%-8s %d件%n", e.getKey().getCode(), e.getValue()));

        // 需求完成度统计
        System.out.println("\n==== 需求完成度 ====");
        Map<DemandOrder, Integer> completedDemand = new HashMap<>();
        for (ProductionAssignment a : solution.getAssignmentList().stream()
                .filter(a -> a.getRouter() != null && a.getProducedItem() != null)
                .toList()) {
            // 追踪产出物料对需求的贡献
            Item item = a.getProducedItem();
            for (DemandOrder d : solution.getDemandList()) {
                if (d.getItem().equals(item)) {
                    completedDemand.merge(d, a.getProducedQuantity(), Integer::sum);
                }
            }
        }
        for (DemandOrder d : solution.getDemandList()) {
            int completed = completedDemand.getOrDefault(d, 0);
            int shortage = Math.max(0, d.getQuantity() - completed);
            String status = shortage == 0 ? "✓ 满足" : String.format("缺少%d件", shortage);
            System.out.printf("需求%s-%d件：已生产%d件 [%s]%n",
                    d.getItem().getCode(), d.getQuantity(), completed, status);
        }
    }

    // 辅助类：表示一个合并的生产批次
    private static class ProductionBatch {
        private TimeSlot startSlot;
        private TimeSlot endSlot;
        private ProductionLine line;
        private Item item;
        private Router router;
        private int totalQuantity;

        ProductionBatch(ProductionAssignment a) {
            this.startSlot = a.getTimeSlot();
            this.endSlot = a.getTimeSlot();
            this.line = a.getLine();
            this.item = a.getProducedItem();
            this.router = a.getRouter();
            this.totalQuantity = a.getProducedQuantity();
        }

        boolean canMerge(ProductionAssignment a) {
            return this.line.equals(a.getLine()) &&
                    this.item.equals(a.getProducedItem()) &&
                    this.router.equals(a.getRouter()) &&
                    a.getTimeSlot().getIndex() == this.endSlot.getIndex() + 1;
        }

        void merge(ProductionAssignment a) {
            this.endSlot = a.getTimeSlot();
            this.totalQuantity += a.getProducedQuantity();
        }

        String getTimeRange() {
            return String.format("%d:00-%d:00", startSlot.getHour(), endSlot.getHour() + 1);
        }

        String getLineName() {
            return line.getCode();
        }

        String getItemName() {
            return item.getCode();
        }

        int getTotalQuantity() {
            return totalQuantity;
        }

        String getRouterName() {
            return router.getCode();
        }
    }
}
