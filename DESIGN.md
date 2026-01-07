# OptaPlanner 车间排程系统设计说明

## 一、系统概述

本项目实现了一个基于OptaPlanner 9.x的多品种小批量离散装配车间智能生产排程系统。系统使用约束流（ConstraintStreams）API实现复杂的生产约束，为订单工序分配最优的生产线和开始时间。

## 二、领域模型

### 2.1 核心实体

#### Order（订单）
- **属性**：
  - `id`: 订单编号
  - `dueDate`: 交货期
  - `priority`: 优先级（1-10，10为最高）
  - `operations`: 包含的工序列表
- **说明**：一个订单包含多个工序，按固定顺序执行

#### Process（工艺类型）
- **属性**：
  - `id`: 工艺编号
  - `name`: 工艺名称
- **说明**：描述加工类型，如Assembly（组装）、Testing（测试）、Packing（包装）

#### Operation（工序）- 规划实体
- **属性**：
  - `id`: 工序编号
  - `order`: 所属订单
  - `process`: 所需工艺
  - `quantity`: 加工数量
  - `standardHours`: 标准工时
  - `sequenceInOrder`: 在订单中的顺序位置
- **规划变量**：
  - `assignedLine`: 分配的生产线（ProductionLine类型）
  - `startTime`: 开始时间戳（Long类型）

#### ProductionLine（生产线）
- **属性**：
  - `code`: 生产线编号
- **说明**：生产资源，同一时间只能加工一个工序

#### Material（物料）
- **属性**：
  - `id`: 物料编号
  - `name`: 物料名称

#### BOMItem（物料清单项）
- **属性**：
  - `id`: BOM项编号
  - `operation`: 关联的工序
  - `material`: 需要的物料
  - `quantity`: 需要数量
- **说明**：定义工序所需的物料及数量

#### Inventory（库存）
- **属性**：
  - `id`: 库存编号
  - `material`: 物料
  - `availableQty`: 可用数量
  - `snapshotTime`: 时间点
- **说明**：物料的库存快照

#### LineProcessCapacity（生产线工艺产能）
- **属性**：
  - `id`: 产能定义编号
  - `line`: 生产线
  - `process`: 工艺
  - `maxUnitsPerHour`: 每小时最大产量
- **说明**：定义某条生产线执行某种工艺的瞬时产能上限

### 2.2 PlanningSolution

**SchedulingSolution** 类是OptaPlanner的问题定义，包含：
- 问题事实（Problem Facts）：生产线、工艺、物料、订单、BOM、库存、产能定义
- 规划实体（Planning Entities）：待排程的工序列表
- 时间窗口：planningWindowStart 和 planningWindowEnd
- 分数：HardSoftScore

## 三、约束规则

### 3.1 硬约束（必须满足）

#### 1. 生产线互斥
- **描述**：同一生产线上的工序时间不能重叠
- **实现**：检查同一生产线上任意两个工序的时间区间是否有交集
- **惩罚**：-1 hard per conflict

#### 2. 工序顺序
- **描述**：同一订单内，后续工序必须在前序工序完成后才能开始
- **实现**：检查工序的startTime是否 >= 前序工序的endTime
- **惩罚**：-1 hard per violation

#### 3. BOM齐套性
- **描述**：工序开始时，所需所有物料的可用库存必须充足
- **实现**：简化版本，检查初始库存是否满足BOM需求
- **惩罚**：-1 hard per missing unit

#### 4. 产能上限
- **描述**：工序的每小时计划产量不能超过生产线对该工艺的maxUnitsPerHour
- **实现**：计算 quantity/standardHours，与LineProcessCapacity的maxUnitsPerHour比较
- **惩罚**：-1 hard per exceeding unit per hour

#### 5. 工艺匹配
- **描述**：工序只能分配给具备其工艺生产能力的生产线
- **实现**：检查是否存在对应的LineProcessCapacity记录
- **惩罚**：-1 hard per mismatch

### 3.2 软约束（优化目标）

#### 1. 最小化总延误
- **描述**：惩罚订单完成时间晚于交货期
- **实现**：计算工序结束时间与订单交货期的差值（小时）× 优先级
- **惩罚**：延误小时数 × 优先级 soft

#### 2. 最小化换线时间
- **描述**：同一生产线上，加工不同工艺的工序切换时产生惩罚
- **实现**：检查同一生产线上连续工序是否切换工艺
- **惩罚**：-100 soft per changeover

#### 3. 均衡负载
- **描述**：尽量避免某些生产线过载而某些闲置
- **实现**：惩罚生产线的总工时
- **惩罚**：工时 × 10 soft per line

## 四、技术实现

### 4.1 技术栈
- Java 17+
- OptaPlanner 9.44.0.Final
- Maven 项目

### 4.2 包结构
```
com.iimsoft.scheduler/
├── domain/              # 领域模型
│   ├── Order.java
│   ├── Process.java
│   ├── Operation.java
│   ├── ProductionLine.java
│   ├── Material.java
│   ├── BOMItem.java
│   ├── Inventory.java
│   ├── LineProcessCapacity.java
│   └── SchedulingSolution.java
├── solver/              # 约束提供类
│   └── SchedulingConstraintProvider.java
└── SchedulingApp.java   # 主应用程序
```

### 4.3 约束提供者

使用 **ConstraintStreams API** 实现约束：
- 类型安全的流式API
- 易于理解和维护
- 性能良好

示例：
```java
Constraint lineResourceConflict(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Operation.class)
        .filter(op -> op.getAssignedLine() != null && op.getStartTime() != null)
        .join(Operation.class,
            Joiners.equal(Operation::getAssignedLine),
            Joiners.lessThan(Operation::getId))
        .filter((op1, op2) -> {
            // 检查时间重叠
            return timeOverlaps(op1, op2);
        })
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("生产线互斥");
}
```

### 4.4 求解器配置

通过代码配置SolverFactory：
```java
SolverFactory<SchedulingSolution> solverFactory = SolverFactory.create(
    new SolverConfig()
        .withSolutionClass(SchedulingSolution.class)
        .withEntityClasses(Operation.class)
        .withConstraintProviderClass(SchedulingConstraintProvider.class)
        .withTerminationSpentLimit(Duration.ofSeconds(30))
);
```

## 五、测试数据

### 5.1 生产线配置
- **Line_A**: 支持 Assembly（50件/时）、Testing（30件/时）
- **Line_B**: 支持 Testing（40件/时）、Packing（60件/时）

### 5.2 订单与工序
- **订单 ORD001**:
  - 优先级: 8
  - 交货期: 当前日期 + 5天
  - 工序1（OP001）: Assembly工艺，45件，工时1.0小时
  - 工序2（OP002）: Testing工艺，20件，工时0.5小时（必须在OP001之后）

### 5.3 BOM与库存
- OP001需要：M001（原材料1）× 10
- OP002需要：M002（原材料2）× 5
- 初始库存：M001: 100件，M002: 50件

## 六、运行方式

### 6.1 编译项目
```bash
cd /path/to/com.iimsoft.scheduler
mvn clean compile
```

### 6.2 运行求解器
```bash
mvn exec:java
```

### 6.3 输出示例
```
=== OptaPlanner 车间排程系统 ===

【初始配置】
生产线配置：
  - Line_A
  - Line_B

工艺类型：
  - Assembly: 组装工艺
  - Testing: 测试工艺
  - Packing: 包装工艺

=== 求解完成 ===

【求解结果】
最终分数: 0hard/500soft

工序排程结果：
  工序 OP001::Assembly
    分配生产线: Line_A
    开始时间: 2026-01-07 09:00
    结束时间: 2026-01-07 10:00
    
  工序 OP002::Testing
    分配生产线: Line_A
    开始时间: 2026-01-07 10:00
    结束时间: 2026-01-07 10:30
```

## 七、扩展建议

### 7.1 短期扩展
1. **班次管理**：增加班次日历，支持白班/夜班排产
2. **设备维护**：考虑设备维护时间窗口
3. **批量约束**：实现最小批量、批次大小约束

### 7.2 中期扩展
1. **动态库存**：实现库存的动态消耗和补充
2. **多资源约束**：增加人员、工装等其他资源约束
3. **紧急插单**：支持紧急订单的动态插入

### 7.3 长期扩展
1. **可视化界面**：开发Gantt图、产能热力图等可视化
2. **实时调度**：支持生产执行反馈和实时重排
3. **多目标优化**：权衡准时率、成本、库存等多个目标

## 八、注意事项

1. **时间精度**：当前使用毫秒级时间戳，步长为1小时
2. **求解时间**：默认30秒，可根据问题规模调整
3. **约束权重**：可根据实际业务需求调整硬约束和软约束的权重
4. **性能优化**：对于大规模问题，可考虑使用增量计分器

## 九、参考资料

- [OptaPlanner官方文档](https://www.optaplanner.org/docs/optaplanner/latest/index.html)
- [ConstraintStreams API指南](https://www.optaplanner.org/docs/optaplanner/latest/constraint-streams/constraint-streams.html)
- [车间调度问题示例](https://www.optaplanner.org/learn/useCases/vehicleRoutingProblem.html)
