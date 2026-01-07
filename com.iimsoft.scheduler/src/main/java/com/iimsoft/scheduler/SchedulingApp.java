package com.iimsoft.scheduler;

import com.iimsoft.scheduler.domain.*;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 车间排程应用主类
 * 功能：创建测试数据，启动求解器，输出结果
 */
public class SchedulingApp {

    public static void main(String[] args) {
        System.out.println("=== OptaPlanner 车间排程系统 ===\n");
        
        // 1. 创建测试数据
        SchedulingSolution problem = createTestData();
        
        // 2. 输出初始配置
        printConfiguration(problem);
        
        // 3. 配置并启动求解器
        SolverFactory<SchedulingSolution> solverFactory = SolverFactory.create(
            new SolverConfig()
                .withSolutionClass(SchedulingSolution.class)
                .withEntityClasses(Operation.class)
                .withConstraintProviderClass(com.iimsoft.scheduler.solver.SchedulingConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofSeconds(30))
        );
        
        Solver<SchedulingSolution> solver = solverFactory.buildSolver();
        
        System.out.println("\n=== 开始求解（30秒）===");
        SchedulingSolution solution = solver.solve(problem);
        
        // 4. 输出结果
        System.out.println("\n=== 求解完成 ===");
        printSolution(solution);
    }

    /**
     * 创建测试数据集
     * 包含：2条生产线、3种工艺、1个订单（2个工序）、相应的BOM和库存
     */
    private static SchedulingSolution createTestData() {
        // 1. 创建生产线
        ProductionLine lineA = new ProductionLine("Line_A");
        ProductionLine lineB = new ProductionLine("Line_B");
        List<ProductionLine> lines = List.of(lineA, lineB);

        // 2. 创建工艺
        Process assembly = new Process("Assembly", "组装工艺");
        Process testing = new Process("Testing", "测试工艺");
        Process packing = new Process("Packing", "包装工艺");
        List<Process> processes = List.of(assembly, testing, packing);

        // 3. 创建物料
        Material mat1 = new Material("M001", "原材料1");
        Material mat2 = new Material("M002", "原材料2");
        List<Material> materials = List.of(mat1, mat2);

        // 4. 创建订单和工序
        LocalDate dueDate = LocalDate.now().plusDays(5);
        Order order1 = new Order("ORD001", dueDate, 8);
        
        // Op1: 组装工艺，数量45，标准工时1.0小时
        Operation op1 = new Operation("OP001", order1, assembly, 45, 1.0, 0);
        // Op2: 测试工艺，数量20，标准工时0.5小时（必须在Op1之后）
        Operation op2 = new Operation("OP002", order1, testing, 20, 0.5, 1);
        
        order1.addOperation(op1);
        order1.addOperation(op2);
        
        List<Order> orders = List.of(order1);
        List<Operation> operations = List.of(op1, op2);

        // 5. 创建生产线工艺产能
        // Line_A: Assembly 50件/时，Testing 30件/时
        // Line_B: Testing 40件/时，Packing 60件/时
        List<LineProcessCapacity> capacities = List.of(
            new LineProcessCapacity("CAP001", lineA, assembly, 50),
            new LineProcessCapacity("CAP002", lineA, testing, 30),
            new LineProcessCapacity("CAP003", lineB, testing, 40),
            new LineProcessCapacity("CAP004", lineB, packing, 60)
        );

        // 6. 创建BOM（物料清单）
        // Op1需要：原材料1 x 10
        // Op2需要：原材料2 x 5
        List<BOMItem> bomItems = List.of(
            new BOMItem("BOM001", op1, mat1, 10),
            new BOMItem("BOM002", op2, mat2, 5)
        );

        // 7. 创建库存（初始库存充足）
        long now = System.currentTimeMillis();
        List<Inventory> inventories = List.of(
            new Inventory("INV001", mat1, 100, now),
            new Inventory("INV002", mat2, 50, now)
        );

        // 8. 定义规划时间窗口（从现在开始，持续7天）
        Long planningStart = now;
        Long planningEnd = now + (7L * 24 * 3600 * 1000); // 7天后

        return new SchedulingSolution(
            lines, processes, materials, orders, 
            bomItems, inventories, capacities, operations,
            planningStart, planningEnd
        );
    }

    /**
     * 输出初始配置信息
     */
    private static void printConfiguration(SchedulingSolution solution) {
        System.out.println("【初始配置】");
        
        System.out.println("\n生产线配置：");
        for (ProductionLine line : solution.getLineList()) {
            System.out.println("  - " + line.getCode());
        }
        
        System.out.println("\n工艺类型：");
        for (Process process : solution.getProcessList()) {
            System.out.println("  - " + process.getId() + ": " + process.getName());
        }
        
        System.out.println("\n生产线工艺产能：");
        for (LineProcessCapacity cap : solution.getLineProcessCapacityList()) {
            System.out.printf("  - %s 执行 %s: %d件/小时%n", 
                cap.getLine().getCode(), 
                cap.getProcess().getId(), 
                cap.getMaxUnitsPerHour());
        }
        
        System.out.println("\n订单信息：");
        for (Order order : solution.getOrderList()) {
            System.out.printf("  订单 %s (优先级=%d, 交货期=%s)%n", 
                order.getId(), order.getPriority(), order.getDueDate());
            for (Operation op : order.getOperations()) {
                System.out.printf("    - 工序 %s: %s, 数量=%d, 工时=%.1f小时%n",
                    op.getId(), op.getProcess().getId(), 
                    op.getQuantity(), op.getStandardHours());
            }
        }
        
        System.out.println("\nBOM物料需求：");
        for (BOMItem bom : solution.getBomItemList()) {
            System.out.printf("  - 工序 %s 需要 %s x %d%n",
                bom.getOperation().getId(),
                bom.getMaterial().getId(),
                bom.getQuantity());
        }
        
        System.out.println("\n初始库存：");
        for (Inventory inv : solution.getInventoryList()) {
            System.out.printf("  - %s: %d 件%n",
                inv.getMaterial().getId(),
                inv.getAvailableQty());
        }
    }

    /**
     * 输出求解结果
     */
    private static void printSolution(SchedulingSolution solution) {
        System.out.println("\n【求解结果】");
        System.out.println("\n最终分数: " + solution.getScore());
        
        System.out.println("\n工序排程结果：");
        List<Operation> scheduledOps = solution.getOperationList().stream()
            .filter(op -> op.getAssignedLine() != null && op.getStartTime() != null)
            .sorted((o1, o2) -> Long.compare(o1.getStartTime(), o2.getStartTime()))
            .collect(Collectors.toList());
        
        if (scheduledOps.isEmpty()) {
            System.out.println("  未找到可行解");
        } else {
            for (Operation op : scheduledOps) {
                String startTimeStr = formatTimestamp(op.getStartTime());
                String endTimeStr = formatTimestamp(op.getEndTime());
                System.out.printf("  工序 %s::%s%n", op.getId(), op.getProcess().getId());
                System.out.printf("    分配生产线: %s%n", op.getAssignedLine().getCode());
                System.out.printf("    开始时间: %s%n", startTimeStr);
                System.out.printf("    结束时间: %s%n", endTimeStr);
                System.out.printf("    加工数量: %d件, 工时: %.1f小时%n", 
                    op.getQuantity(), op.getStandardHours());
            }
        }
        
        // 统计各生产线负载
        System.out.println("\n生产线负载统计：");
        Map<ProductionLine, Double> lineLoads = solution.getOperationList().stream()
            .filter(op -> op.getAssignedLine() != null)
            .collect(Collectors.groupingBy(
                Operation::getAssignedLine,
                Collectors.summingDouble(Operation::getStandardHours)
            ));
        
        for (ProductionLine line : solution.getLineList()) {
            double load = lineLoads.getOrDefault(line, 0.0);
            System.out.printf("  %s: %.1f 小时%n", line.getCode(), load);
        }
        
        // 订单完成情况
        System.out.println("\n订单完成情况：");
        for (Order order : solution.getOrderList()) {
            Long maxEndTime = order.getOperations().stream()
                .map(Operation::getEndTime)
                .filter(t -> t != null)
                .max(Long::compare)
                .orElse(null);
            
            if (maxEndTime != null) {
                String finishTimeStr = formatTimestamp(maxEndTime);
                long dueDateMillis = order.getDueDate().atStartOfDay()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                boolean onTime = maxEndTime <= dueDateMillis;
                System.out.printf("  订单 %s: 完成时间=%s, 交货期=%s, %s%n",
                    order.getId(),
                    finishTimeStr,
                    order.getDueDate(),
                    onTime ? "✓ 准时" : "✗ 延误");
            } else {
                System.out.printf("  订单 %s: 未完成排程%n", order.getId());
            }
        }
    }

    /**
     * 格式化时间戳为可读字符串
     */
    private static String formatTimestamp(Long timestamp) {
        if (timestamp == null) return "未安排";
        return java.time.Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
