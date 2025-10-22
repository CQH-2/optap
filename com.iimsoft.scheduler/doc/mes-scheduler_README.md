# MES 生产排程模块（Java 8）使用手册

本模块提供离散制造场景下的“拉式计划 + 有限产能排程 + 换型时间 + 种群优化（加权 GA 与 NSGA-II）+ 瓶颈分析”的一体化原型，采用 Java 8 与 Maven 构建，默认以内存仓储运行，便于快速验证与二次开发。

适用场景
- 多产线、多物料，且“同一物料在不同产线速率不同”
- 同一班次内存在物料切换，需要考虑换型时间
- 以“下游需求驱动（PULL）”逐级拉动上游子件与原料，避免过早展开与备产
- 需要基于目标（迟期最小/换型最少/折中）优化排程顺序，并查看瓶颈

---

## 1. 快速开始

前置要求
- JDK 1.8+
- Maven 3.6+

构建
```bash
# 在仓库根目录
mvn -q -f mes-scheduler/pom.xml -DskipTests package
```

运行（两种方式）
```bash
# 方式一：直接用 java 执行
java -cp mes-scheduler/target/mes-scheduler-1.0.0.jar com.iimsoft.scheduler.app.App

# 方式二：使用 Maven Exec 插件
mvn -q -f mes-scheduler/pom.xml exec:java
```

切换优化器（加权 GA / NSGA-II）
- 打开 com.iimsoft.scheduler.app.App，顶部布尔开关：
```java
// App.main 开头
boolean useNsga2 = false; // false: 加权 GA；true: NSGA-II
```

运行输出
- 输入需求与库存/在途
- 拉式展开后的计划订单（PlannedOrder）
- 生产排程工单与排程段（WorkOrder + ScheduledSegment）
- 瓶颈分析报告（产线/班次利用率、换型次数、迟期贡献）

---

## 2. 模块结构

```
mes-scheduler/
├─ pom.xml                 # Maven 构建（Java 1.8，含 exec 插件）
├─ .gitignore              # 忽略 target/
├─ README.md               # 本手册
└─ src/main/java/com/iimsoft/scheduler
   ├─ model/               # 领域模型（物料、BOM、工艺、产线、班次、需求、库存、计划与工单）
   ├─ repository/          # 仓储接口（Item/BOM/Routing/Line/Demand/Inventory/Changeover）
   │  └─ memory/           # 内存实现
   ├─ mrp/                 # MRPEngine（PUSH/PULL）
   ├─ pull/                # PullPlanner（拉式展开）
   ├─ schedule/            # Scheduler（有限产能反排 + 换型）
   ├─ opt/                 # 加权 GA 与 NSGA-II 优化器
   ├─ analysis/            # 瓶颈分析
   ├─ data/                # DemoData（演示数据装载）
   └─ app/                 # App（演示入口）
```

---

## 3. 核心概念与数据模型（model）

- Item / ItemType(FG/SFG/RM)：物料与类型
- BOM / BOMComponent：父子用量（不含工艺层工序，简化按成品层级）
- Routing / LineCapability：物料可生产的产线与该线的速率（件/小时）
- ProductionLine / Shift：产线与班次（Shift 含 start/end，用 capacityHours() 计算时长）
- Demand：外部需求（item、qty、dueDate、priority）
- Inventory / ScheduledReceipt：库存与在途到货
- PlannedOrder（计划）：item、qty、dueDate（完工/到货）、releaseDate（开工/下达）、planType（MANUFACTURE/PURCHASE）
- WorkOrder / ScheduledSegment（排程结果）：工单及其分配到“产线-班次”的生产段

---

## 4. 拉式展开（PullPlanner）算法

目标
- 从“下游制造单（通常为成品 FG）”出发，逐级拉动上游子件（SFG/RM），确保“父件开工日”子件可用。

要点
- 子件需求日 = 父件的 releaseDate（开工日）
- 可用量优先级：库存 → 实际在途（按日期 FIFO）→ 本轮计划在途
- 分配账本避免重复分配：
  - allocatedReceipts[date][item]：已从实际在途分配的数量
  - allocatedPlannedReceipts[date][item]：已从计划在途分配的数量
- 仅对净缺口创建计划：RM→采购单（PO）、SFG/FG→制造单（MO）
- 批量：向上取整至 lotSize
- 损耗：按 scrapRate 反推毛需求 qty/(1-scrap)
- 制造提前期估算：按该物料最快 item–line 速率估算工时，按 16h/天折算为天数（>=1）

流程
1) 从顶层制造单（由 MRPEngine 的 PULL 模式生成）开始，按开工日升序处理
2) 对每个父件的 BOM 子件：
   - 计算毛需求 → 计算可用量 → 分配可用量
   - 对缺口部分创建 PO/MO 并记入“计划在途”，同时立即在账本上分配到该父件
   - 若创建了 MO，则递归为该子件的开工日继续拉式展开

优势
- 避免推式 MRP 的“全量提前展开”，降低在制与库存；实现“需要时到”

---

## 5. 有限产能排程（Scheduler）

目标
- 在 releaseDate ~ dueDate 窗口内，按“产线与班次”反向排程（靠近 dueDate 开始向前占用班次尾部），考虑换型时间与线速率差异。

要点
- 候选产线：来自 Routing 中该物料的 capabilities（速率 > 0）
- 产线优先级：对该物料的速率高 → 优先
- 窗口：仅取落在 MO.releaseDate~MO.dueDate 的班次；若不足，可向前再扩展 7 天
- 班次容量追踪：remainingHours[shiftId]
- 换型：同一班次末尾从上个物料切到当前物料，先扣减 setup（ChangeoverRepository 提供）
- 段生成：在班次“末尾”先扣换型、再向前占用生产时间
- 接口重载：
  - schedule(pos) 默认“到期日优先”
  - schedule(pos, true) 尊重输入顺序（供优化器使用）

简化假设
- 单工序成组时间（不建模工序拆分/并行），换型在班次内统一处理；可迭代扩展为工序级 APS

---

## 6. 优化器（opt）

### 6.1 加权 GA（单目标）
- 染色体：制造单顺序的一个排列
- 适应度：1 / (1 + α·总迟期小时 + β·换型次数)
- 操作：锦标赛选择 + OX（Order Crossover）交叉 + 交换变异
- 评估：调用 schedule(pos, true) 产生排程，用总迟期与换型数打分
- 配置：GAConfig（populationSize, generations, crossoverRate, mutationRate, randomSeed, objective, alphaLateHours, betaChangeovers）

适用
- 业务偏好固定（例如“迟期优先，少量关注换型”），可直接用 α/β 体现权衡

### 6.2 NSGA-II（多目标）
- 目标：同时最小化（总迟期小时，换型次数），不需预先设权重
- 核心：快速非支配排序 + 拥挤距离，保留帕累托前沿
- 结果：返回多组可行“顺序”解（Pareto 解集），可选择膝点或最小迟期解
- 配置：NSGA2Config（populationSize, generations, crossoverRate, mutationRate, randomSeed）

适用
- 希望看到“迟期与换型”的完整权衡面，再由业务选择

参数建议（起步）
- GA：pop=30, gen=60, cr=0.9, mr=0.2, α=1.0, β=0.1
- NSGA-II：pop=40, gen=80, cr=0.9, mr=0.25
- 大规模数据时可提升种群与代数（评估耗时近似线性增加）

---

## 7. 瓶颈分析（analysis.BottleneckAnalyzer）

输出
- 产线级统计：usedHours（生产+估算换型）、capacityHours、changeovers、utilization、lateHoursImpact
- 班次 Top 列表：按利用率排序，定位高占用/高换型的具体班次

使用
- App 末尾会打印“=== 瓶颈分析 ===”报告
- 可据此制定：加班/调整优先顺序/减换型的分组策略

---

## 8. 配置与扩展

数据来源（仓储接口）
- repository：
  - ItemRepository, BOMRepository, RoutingRepository, LineRepository, DemandRepository, InventoryRepository, ChangeoverRepository
- 默认使用 repository.memory 的内存实现
- 对接数据库：实现上述接口的 DB 版本（JPA/MyBatis/JDBC），在 App 中替换注入

常改项位置
- DemoData：需求、BOM、线速率、班次日历、库存/在途、换型矩阵
- ChangeoverRepository：lineId/fromItem/toItem → setupMinutes
- GAConfig/NSGA2Config：优化参数与目标
- MRPEngine/PullPlanner：
  - roundLot（批量策略）、applyScrap（损耗）、estimateMfgLeadDays（提前期估算）
- Scheduler：
  - 产线/班次选择排序规则、是否强制“不跨线/不拆单”、是否允许前述 7 天窗口外扩

可插拔策略（SPI）建议（可后续引入）
- LeadTimeEstimator：制造提前期估算（瓶颈/工序/日历维度）
- LotSizingPolicy：批量策略（最小/倍数/周期/经济批量）
- PriorityRule：计划排序规则（EDD、CR、SLK 等）
- LineSelector：产线选择约束（固定线、黑白名单、最少换型/不拆单）

---

## 9. 示例修改

修改换型矩阵（InMemoryChangeoverRepository）
```java
// DemoData.build(...)
changeoverRepo.put("L2", "FG100", "SFG200", 60);
changeoverRepo.put("L2", "SFG200", "FG100", 60);
// 新增：同线同品切换 0 分钟（默认即为 0，可不配）
```

调整线速率（Routing）
```java
Routing rFG = new Routing("FG100");
rFG.capabilities().add(new LineCapability("L1", 22.0));
rFG.capabilities().add(new LineCapability("L2", 15.0));
```

调整优化器参数（App）
```java
GAConfig cfg = GAConfig.defaultConfig();
// or new GAConfig(40, 80, 0.9, 0.2, 123L, Objective.WEIGHTED, 1.0, 0.2);
```

---

## 10. 常见问题（FAQ）

1) 运行报 Java 版本不匹配？
- 请确认使用 JDK 1.8，并在 pom.xml 中已设 source/target 1.8（已内置）

2) 合并 PR 显示 Draft/WIP？
- 在 PR 页面点击 “Ready for review” 或去掉标题中的 [WIP]，再合并

3) 输出有迟期（LATE）？
- 班次容量不足或产能速率偏低；可尝试：调高速率、增加班次、提前 releaseDate、在优化器中使用 NSGA-II 选择最小迟期解

4) 换型太多？
- 提升 GA/NSGA-II 代数/种群；或在 Scheduler 中添加“不跨线/不拆单”偏好；或通过 LineSelector SPI 约束

---

## 11. 设计与算法要点（速览）

- PULL 展开：子件需求日=父件开工日，先扣库存/在途/计划在途；缺口才新建 PO/MO；用分配账本避免重复占用
- 排程：按“对该物料更快的产线优先”，在 dueDate 附近开始向前安排班次尾部，切换物料先扣换型
- 优化：
  - 加权 GA：单目标，权重可调
  - NSGA-II：多目标，非支配排序 + 拥挤距离，生成帕累托前沿
- 瓶颈：产线/班次利用率与换型、迟期贡献，定位改进点

---

## 12. 后续计划（Roadmap）

- 工序级 APS（Run/Setup/Queue/Move/Wait）、并行与资源型约束
- 看板/CONWIP 与在制上限
- 数据库仓储与 REST API / 甘特图可视化
- 可插拔策略（SPI）与参数化规则集

---

## 13. 许可证

如需开源/商用条款，请在仓库根或本模块添加 LICENSE 文件并在此处说明。

---

如需我将内存仓储替换为数据库版、提供 API 或前端可视化，请在 PR 中留言或开新 Issue 说明具体字段与规则，我会基于本模块快速扩展。