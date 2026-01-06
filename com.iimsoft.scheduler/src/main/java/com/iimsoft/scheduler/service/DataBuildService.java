package com.iimsoft.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.dto.ImportDTOs;

import java.io.File;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 构建数据（保留“分桶”，不再按物料聚合成单一需求）。
 * - 每个 DemandDTO -> 一个 DemandOrder（桶）
 * - 父桶按 BOM 分解出子桶（按子件 leadTime 前置）
 * - 初始库存按“同物料桶的到期升序”逐桶抵扣
 * - 可选：同物料同到期日的多个来源合并为一个桶（item+dueDate 维度）
 */
public class DataBuildService {

    private final ObjectMapper mapper = new ObjectMapper();

    public ProductionSchedule buildScheduleFromFile(String jsonPath) throws Exception {
        ImportDTOs.Root dto = mapper.readValue(new File(jsonPath), ImportDTOs.Root.class);
        int workStart = 8, workEnd = 19;

        LocalDate earliestDue = dto.demands == null || dto.demands.isEmpty()
                ? LocalDate.now()
                : dto.demands.stream().map(d -> LocalDate.parse(d.dueDate)).min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate latestDue = dto.demands == null || dto.demands.isEmpty()
                ? earliestDue
                : dto.demands.stream().map(d -> LocalDate.parse(d.dueDate)).max(LocalDate::compareTo).orElse(earliestDue);
        int maxLead = dto.items == null || dto.items.isEmpty()
                ? 0
                : dto.items.stream().mapToInt(i -> i.leadTime).max().orElse(0);

        // 如需固定前推3天，可改为 earliestDue.minusDays(3)
        LocalDate slotStart = earliestDue.minusDays(maxLead);
        LocalDate slotEnd = latestDue;

        return buildSchedule(dto, slotStart, slotEnd, workStart, workEnd);
    }

    public ProductionSchedule buildScheduleFromFile(String jsonPath,
                                                    LocalDate slotStart, LocalDate slotEnd,
                                                    int workStart, int workEnd) throws Exception {
        ImportDTOs.Root dto = mapper.readValue(new File(jsonPath), ImportDTOs.Root.class);
        return buildSchedule(dto, slotStart, slotEnd, workStart, workEnd);
    }

    /**
     * 指定生产开始日期，自动计算排产时间窗口
     * 排产时间范围：从 productionStartDate 到最晚需求到期日期
     * @param jsonPath 数据文件路径
     * @param productionStartDate 生产开始日期
     * @param workStart 班次开始小时（如 8）
     * @param workEnd 班次结束小时（如 19）
     * @return 生产计划
     */
    public ProductionSchedule buildScheduleWithProductionStartDate(String jsonPath,
                                                                   LocalDate productionStartDate,
                                                                   int workStart, int workEnd) throws Exception {
        ImportDTOs.Root dto = mapper.readValue(new File(jsonPath), ImportDTOs.Root.class);
        
        // 计算最晚需求到期日期
        LocalDate latestDue = dto.demands == null || dto.demands.isEmpty()
                ? LocalDate.now()
                : dto.demands.stream().map(d -> LocalDate.parse(d.dueDate)).max(LocalDate::compareTo).orElse(LocalDate.now());
        
        // 排产时间范围：从指定的生产开始日期到最晚需求到期日期
        LocalDate slotStart = productionStartDate;
        LocalDate slotEnd = latestDue;
        
        return buildSchedule(dto, slotStart, slotEnd, workStart, workEnd);
    }

    /**
     * 智能排产：根据产能自动判断是否需要夜班
     * 排产逻辑：
     * 1. 计算BOM展开后的总需求量（包含所有层级的物料）
     * 2. 计算白班产能（考虑工艺速度约束）
     * 3. 如果白班产能不足以满足需求（考虑90%产能利用率），自动启用夜班
     * 4. 从需求时间倒排到指定开始日期
     * 
     * @param jsonPath 数据文件路径
     * @param productionStartDate 生产开始日期
     * @return 生产计划（自动包含白班/夜班时间槽）
     */
    public ProductionSchedule buildScheduleWithShiftPlanning(String jsonPath,
                                                             LocalDate productionStartDate) throws Exception {
        ImportDTOs.Root dto = mapper.readValue(new File(jsonPath), ImportDTOs.Root.class);
        
        // 1. 构建Item映射和BOM关系
        Map<String, Item> itemMap = new LinkedHashMap<>();
        if (dto.items != null) {
            for (ImportDTOs.ItemDTO it : dto.items) {
                ItemType type = ItemType.GENERIC;
                if (it.itemType != null) {
                    try {
                        type = ItemType.valueOf(it.itemType.toUpperCase());
                    } catch (IllegalArgumentException e) {}
                }
                itemMap.put(it.code, new Item(it.code, it.name, it.leadTime, type));
            }
        }
        
        // BOM映射
        Map<String, List<ImportDTOs.BomArcDTO>> bomMap = new HashMap<>();
        if (dto.bomArcs != null) {
            for (ImportDTOs.BomArcDTO arc : dto.bomArcs) {
                bomMap.computeIfAbsent(arc.parent, k -> new ArrayList<>()).add(arc);
            }
        }
        
        // 2. 计算BOM展开后的总需求量
        Map<String, Integer> totalDemandByItem = new HashMap<>();
        if (dto.demands != null) {
            for (ImportDTOs.DemandDTO demand : dto.demands) {
                expandBomDemand(demand.item, demand.quantity, totalDemandByItem, bomMap);
            }
        }
        
        int totalDemand = totalDemandByItem.values().stream().mapToInt(Integer::intValue).sum();
        int finalProductDemand = dto.demands == null ? 0 : 
            dto.demands.stream().mapToInt(d -> d.quantity).sum();
        
        // 3. 计算最晚需求到期日期
        LocalDate latestDue = dto.demands == null || dto.demands.isEmpty()
                ? LocalDate.now()
                : dto.demands.stream()
                    .map(d -> LocalDate.parse(d.dueDate))
                    .max(LocalDate::compareTo)
                    .orElse(LocalDate.now());
        
        // 4. 计算白班产能（考虑工艺瓶颈）
        long daysBetween = ChronoUnit.DAYS.between(productionStartDate, latestDue) + 1;
        int dayShiftHours = Shift.DAY.getWorkingHours(); // 12小时
        
        // 工艺速度映射（按物料分组，取最小速度作为瓶颈）
        Map<String, Integer> itemSpeedMap = new HashMap<>();
        if (dto.routers != null) {
            for (ImportDTOs.RouterDTO r : dto.routers) {
                itemSpeedMap.merge(r.item, r.speedPerHour, Math::min);
            }
        }
        
        // 产线数量
        int lineCount = dto.lines == null ? 1 : dto.lines.size();
        
        // 计算各物料的理论产能并找出瓶颈
        long minCapacityRatio = Long.MAX_VALUE;
        String bottleneckItem = "";
        for (Map.Entry<String, Integer> entry : totalDemandByItem.entrySet()) {
            String itemCode = entry.getKey();
            int demand = entry.getValue();
            int speed = itemSpeedMap.getOrDefault(itemCode, 20); // 默认20件/小时
            long capacity = daysBetween * dayShiftHours * speed * lineCount;
            if (demand > 0) {
                long ratio = capacity * 100 / demand;
                if (ratio < minCapacityRatio) {
                    minCapacityRatio = ratio;
                    bottleneckItem = itemCode;
                }
            }
        }
        
        // 白班总产能（用平均速度估算）
        int avgSpeed = dto.routers == null || dto.routers.isEmpty() ? 20 :
            dto.routers.stream().mapToInt(r -> r.speedPerHour).sum() / dto.routers.size();
        long dayShiftCapacity = daysBetween * dayShiftHours * avgSpeed * lineCount;
        
        // 5. 判断是否需要夜班
        // 策略：如果任何物料的需求超过白班产能的90%，或产能利用率>90%，则启用夜班
        boolean needNightShift = (totalDemand > dayShiftCapacity * 0.90) || (minCapacityRatio < 110);
        
        System.out.println("===== 产能评估 =====");
        System.out.printf("成品需求量: %d 件%n", finalProductDemand);
        System.out.printf("总需求量(含BOM): %d 件%n", totalDemand);
        System.out.printf("生产周期: %d 天 (从 %s 到 %s)%n", daysBetween, productionStartDate, latestDue);
        System.out.printf("白班产能: %d 件 (%d天 × %d小时 × %d件/时 × %d产线)%n", 
            dayShiftCapacity, daysBetween, dayShiftHours, avgSpeed, lineCount);
        System.out.printf("产能利用率: %.1f%%%n", (totalDemand * 100.0 / dayShiftCapacity));
        if (!bottleneckItem.isEmpty()) {
            System.out.printf("瓶颈物料: %s (产能裕度 %d%%)%n", bottleneckItem, minCapacityRatio);
        }
        System.out.printf("是否启用夜班: %s%s%n", needNightShift ? "是" : "否",
            needNightShift ? " (需求超过白班产能90%或存在瓶颈)" : "");
        System.out.println("==================");
        
        // 6. 生成时间槽（带班次信息）
        List<TimeSlot> timeSlots = generateTimeSlotsWithShifts(
            productionStartDate, latestDue, needNightShift);
        
        // 7. 构建排产计划
        return buildScheduleWithTimeSlots(dto, productionStartDate, latestDue, timeSlots);
    }
    
    /**
     * 递归展开BOM需求
     * @param itemCode 物料代码
     * @param quantity 需求数量
     * @param totalDemand 累计需求映射
     * @param bomMap BOM关系映射
     */
    private void expandBomDemand(String itemCode, int quantity, 
                                Map<String, Integer> totalDemand,
                                Map<String, List<ImportDTOs.BomArcDTO>> bomMap) {
        // 累加当前物料需求
        totalDemand.merge(itemCode, quantity, Integer::sum);
        
        // 递归展开子件需求
        List<ImportDTOs.BomArcDTO> children = bomMap.get(itemCode);
        if (children != null) {
            for (ImportDTOs.BomArcDTO arc : children) {
                expandBomDemand(arc.child, quantity * arc.quantityPerParent, totalDemand, bomMap);
            }
        }
    }

    /**
     * 生成时间槽（支持白班+夜班）
     * 白班：8:00-19:00
     * 夜班：20:00-次日7:00
     */
    private List<TimeSlot> generateTimeSlotsWithShifts(LocalDate start, LocalDate end, 
                                                       boolean includeNightShift) {
        List<TimeSlot> slots = new ArrayList<>();
        int idx = 0;
        
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            // 白班：8:00-19:00
            for (int h = 8; h <= 19; h++) {
                slots.add(new TimeSlot(d, h, idx++, Shift.DAY));
            }
            
            // 夜班：20:00-次日7:00（如果需要）
            if (includeNightShift) {
                // 当天 20:00-23:00
                for (int h = 20; h <= 23; h++) {
                    slots.add(new TimeSlot(d, h, idx++, Shift.NIGHT));
                }
                // 次日 0:00-7:00
                LocalDate nextDay = d.plusDays(1);
                if (!nextDay.isAfter(end)) { // 确保不超出结束日期
                    for (int h = 0; h <= 7; h++) {
                        slots.add(new TimeSlot(nextDay, h, idx++, Shift.NIGHT));
                    }
                }
            }
        }
        
        return slots;
    }
    
    /**
     * 使用指定的时间槽构建排产计划
     */
    private ProductionSchedule buildScheduleWithTimeSlots(ImportDTOs.Root dto,
                                                          LocalDate slotStart, LocalDate slotEnd,
                                                          List<TimeSlot> timeSlots) {
        // 复用原有的 buildSchedule 逻辑，但传入预生成的 timeSlots
        // 1) Items
        Map<String, Item> itemMap = new LinkedHashMap<>();
        if (dto.items != null) {
            for (ImportDTOs.ItemDTO it : dto.items) {
                ItemType type = ItemType.GENERIC;
                if (it.itemType != null) {
                    try {
                        type = ItemType.valueOf(it.itemType.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        // 如果解析失败，使用默认值GENERIC
                    }
                }
                itemMap.put(it.code, new Item(it.code, it.name, it.leadTime, type));
            }
        }

        // 2) BOM
        List<BomArc> bomArcs = new ArrayList<>();
        if (dto.bomArcs != null) {
            for (ImportDTOs.BomArcDTO arc : dto.bomArcs) {
                Item parent = itemMap.get(arc.parent);
                Item child = itemMap.get(arc.child);
                if (parent != null && child != null) {
                    bomArcs.add(new BomArc(parent, child, arc.quantityPerParent));
                }
            }
        }

        // 3) Routers（第一遍：创建所有Router对象）
        Map<String, Router> routerMap = new LinkedHashMap<>();
        if (dto.routers != null) {
            for (ImportDTOs.RouterDTO r : dto.routers) {
                Item item = itemMap.get(r.item);
                if (item != null) {
                    int setupTime = r.setupTimeHours > 0 ? r.setupTimeHours : 0;
                    int minBatch = r.minBatchSize > 0 ? r.minBatchSize : 0;
                    routerMap.put(r.code, new Router(r.code, item, r.speedPerHour, setupTime, minBatch));
                }
            }
        }
        
        // 3.1) Routers（第二遍：设置前置工序依赖关系）
        if (dto.routers != null) {
            for (ImportDTOs.RouterDTO r : dto.routers) {
                Router router = routerMap.get(r.code);
                if (router != null && r.predecessors != null) {
                    for (String predCode : r.predecessors) {
                        Router predecessor = routerMap.get(predCode);
                        if (predecessor != null) {
                            router.addPredecessor(predecessor);
                        }
                    }
                }
            }
        }
        
        List<Router> routers = new ArrayList<>(routerMap.values());

        // 4) Lines
        List<ProductionLine> productionLines = new ArrayList<>();
        if (dto.lines != null) {
            for (ImportDTOs.LineDTO l : dto.lines) {
                ProductionLine productionLine = new ProductionLine(l.code);
                List<Router> supported = l.supportedRouters == null
                        ? List.of()
                        : l.supportedRouters.stream().map(routerMap::get).filter(Objects::nonNull).collect(Collectors.toList());
                productionLine.setSupportedRouters(supported);
                productionLines.add(productionLine);
            }
        }

        // 5) Inventories
        List<ItemInventory> inventories = new ArrayList<>();
        if (dto.inventories != null) {
            for (ImportDTOs.InventoryDTO inv : dto.inventories) {
                Item it = itemMap.get(inv.item);
                if (it != null) {
                    ItemInventory ii = new ItemInventory(it, inv.initialOnHand);
                    ii.setSafetyStock(inv.safetyStock);
                    inventories.add(ii);
                }
            }
        }

        // 6) 需求处理 - 仅使用原始需求，不添加BOM派生需求
        List<DemandOrder> bucketDemands = new ArrayList<>();
        if (dto.demands != null) {
            Map<String, Map<String, Object>> demandMap = new LinkedHashMap<>();
            
            for (ImportDTOs.DemandDTO dmd : dto.demands) {
                Item it = itemMap.get(dmd.item);
                if (it == null) continue;
                LocalDate dueDate = LocalDate.parse(dmd.dueDate);
                String key = it.getCode() + "@" + dueDate;
                
                int priority = dmd.priority > 0 ? dmd.priority : 5;
                demandMap.compute(key, (k, v) -> {
                    if (v == null) {
                        v = new HashMap<>();
                        v.put("item", it);
                        v.put("dueDate", dueDate);
                        v.put("qty", dmd.quantity);
                        v.put("priority", priority);
                    } else {
                        int existQty = (int) v.get("qty");
                        int existPriority = (int) v.get("priority");
                        v.put("qty", existQty + dmd.quantity);
                        v.put("priority", Math.max(priority, existPriority));
                    }
                    return v;
                });
            }
            
            for (Map<String, Object> demand : demandMap.values()) {
                Item it = (Item) demand.get("item");
                LocalDate dueDate = (LocalDate) demand.get("dueDate");
                int qty = (int) demand.get("qty");
                int priority = (int) demand.get("priority");
                int dueIdx = lastIndexForDateOrClamp(timeSlots, dueDate);
                bucketDemands.add(new DemandOrder(it, qty, dueDate, dueIdx, priority));
            }
        }

        // 调试输出
        System.out.printf("✓ 原始需求数量: %d%n", bucketDemands.size());
        for (DemandOrder d : bucketDemands) {
            System.out.printf("  - %s %d件，截止 %s，优先级 %d%n", 
                d.getItem().getCode(), d.getQuantity(), d.getDueDate(), d.getPriority());
        }

        // 7) BOM分解：为每个原始需求生成派生需求（用于完成BOM链）
        // 派生需求优先级设为0，标记为系统自动生成（不显示给用户）
        List<DemandOrder> bomBuckets = new ArrayList<>();
        for (DemandOrder parentBucket : new ArrayList<>(bucketDemands)) {
            for (BomArc arc : bomArcs) {
                if (arc.getParent().equals(parentBucket.getItem())) {
                    Item child = arc.getChild();
                    int qty = parentBucket.getQuantity() * arc.getQuantityPerParent();
                    LocalDate childDue = parentBucket.getDueDate().minusDays(Math.max(0, child.getLeadTime()));
                    if (childDue.isBefore(slotStart)) childDue = slotStart;
                    if (childDue.isAfter(slotEnd)) childDue = slotEnd;
                    int childIdx = lastIndexForDateOrClamp(timeSlots, childDue);
                    // BOM派生需求优先级设为0，避免干扰优化器决策
                    bomBuckets.add(new DemandOrder(child, qty, childDue, childIdx, 0));
                }
            }
        }
        bucketDemands.addAll(bomBuckets);
        bucketDemands = mergeByItemAndDueDate(bucketDemands, timeSlots);

        List<DemandOrder> finalDemands = new ArrayList<>(bucketDemands);

        // 8) Assignments
        List<ProductionAssignment> assignments = new ArrayList<>();
        long id = 1L;
        for (ProductionLine productionLine : productionLines) {
            for (TimeSlot slot : timeSlots) {
                assignments.add(new ProductionAssignment(id++, productionLine, slot));
            }
        }

        return new ProductionSchedule(
                routers, productionLines, timeSlots, bomArcs, inventories, finalDemands, assignments
        );
    }

    public ProductionSchedule buildSchedule(ImportDTOs.Root dto,
                                            LocalDate slotStart, LocalDate slotEnd,
                                            int workStart, int workEnd) {
        // 1) Items
        Map<String, Item> itemMap = new LinkedHashMap<>();
        if (dto.items != null) {
            for (ImportDTOs.ItemDTO it : dto.items) {
                ItemType type = ItemType.GENERIC;
                if (it.itemType != null) {
                    try {
                        type = ItemType.valueOf(it.itemType.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        // 如果解析失败，使用默认值GENERIC
                    }
                }
                itemMap.put(it.code, new Item(it.code, it.name, it.leadTime, type));
            }
        }

        // 2) BOM
        List<BomArc> bomArcs = new ArrayList<>();
        if (dto.bomArcs != null) {
            for (ImportDTOs.BomArcDTO arc : dto.bomArcs) {
                Item parent = itemMap.get(arc.parent);
                Item child = itemMap.get(arc.child);
                if (parent != null && child != null) {
                    bomArcs.add(new BomArc(parent, child, arc.quantityPerParent));
                }
            }
        }

        // 3) Routers（第一遍：创建所有Router对象）
        Map<String, Router> routerMap = new LinkedHashMap<>();
        if (dto.routers != null) {
            for (ImportDTOs.RouterDTO r : dto.routers) {
                Item item = itemMap.get(r.item);
                if (item != null) {
                    int setupTime = r.setupTimeHours > 0 ? r.setupTimeHours : 0;
                    int minBatch = r.minBatchSize > 0 ? r.minBatchSize : 0;
                    routerMap.put(r.code, new Router(r.code, item, r.speedPerHour, setupTime, minBatch));
                }
            }
        }
        
        // 3.1) Routers（第二遍：设置前置工序依赖关系）
        if (dto.routers != null) {
            for (ImportDTOs.RouterDTO r : dto.routers) {
                Router router = routerMap.get(r.code);
                if (router != null && r.predecessors != null) {
                    for (String predCode : r.predecessors) {
                        Router predecessor = routerMap.get(predCode);
                        if (predecessor != null) {
                            router.addPredecessor(predecessor);
                        }
                    }
                }
            }
        }
        
        List<Router> routers = new ArrayList<>(routerMap.values());

        // 4) Lines
        List<ProductionLine> productionLines = new ArrayList<>();
        if (dto.lines != null) {
            for (ImportDTOs.LineDTO l : dto.lines) {
                ProductionLine productionLine = new ProductionLine(l.code);
                List<Router> supported = l.supportedRouters == null
                        ? List.of()
                        : l.supportedRouters.stream().map(routerMap::get).filter(Objects::nonNull).collect(Collectors.toList());
                productionLine.setSupportedRouters(supported);
                productionLines.add(productionLine);
            }
        }

        // 5) Inventories
        List<ItemInventory> inventories = new ArrayList<>();
        if (dto.inventories != null) {
            for (ImportDTOs.InventoryDTO inv : dto.inventories) {
                Item it = itemMap.get(inv.item);
                if (it != null) {
                    ItemInventory ii = new ItemInventory(it, inv.initialOnHand);
                    ii.setSafetyStock(inv.safetyStock);
                    inventories.add(ii);
                }
            }
        }
        Map<Item, Integer> initialOnHandMap = inventories.stream()
                .collect(Collectors.toMap(ItemInventory::getItem, ItemInventory::getInitialOnHand, (a, b) -> b, LinkedHashMap::new));
        Map<Item, Integer> safetyStockMap = inventories.stream()
                .collect(Collectors.toMap(ItemInventory::getItem, ItemInventory::getSafetyStock, (a, b) -> b, LinkedHashMap::new));

        // 6) TimeSlots
        List<TimeSlot> timeSlots = generateTimeSlots(slotStart, slotEnd, workStart, workEnd);

        // 7) 基础需求桶（DTO -> DemandOrder，按 item+dueDate 形成桶）
        List<DemandOrder> bucketDemands = new ArrayList<>();
        if (dto.demands != null) {
            // 需求优先级映射：key=(item+dueDate), value=(priority, qty)
            Map<String, Map<String, Object>> demandMap = new LinkedHashMap<>();
            
            for (ImportDTOs.DemandDTO dmd : dto.demands) {
                Item it = itemMap.get(dmd.item);
                if (it == null) continue;
                LocalDate dueDate = LocalDate.parse(dmd.dueDate);
                String key = it.getCode() + "@" + dueDate;
                
                // 如果同一桶有多个需求，取最高优先级（最大值）
                int priority = dmd.priority > 0 ? dmd.priority : 5;
                demandMap.compute(key, (k, v) -> {
                    if (v == null) {
                        v = new HashMap<>();
                        v.put("item", it);
                        v.put("dueDate", dueDate);
                        v.put("qty", dmd.quantity);
                        v.put("priority", priority);
                    } else {
                        int existQty = (int) v.get("qty");
                        int existPriority = (int) v.get("priority");
                        v.put("qty", existQty + dmd.quantity);
                        v.put("priority", Math.max(priority, existPriority));
                    }
                    return v;
                });
            }
            
            for (Map<String, Object> demand : demandMap.values()) {
                Item it = (Item) demand.get("item");
                LocalDate dueDate = (LocalDate) demand.get("dueDate");
                int qty = (int) demand.get("qty");
                int priority = (int) demand.get("priority");
                int dueIdx = lastIndexForDateOrClamp(timeSlots, dueDate);
                bucketDemands.add(new DemandOrder(it, qty, dueDate, dueIdx, priority));
            }
        }

        // 8) BOM 分解：父桶 -> 子桶（前置 leadTime，超出时间窗 clamp，继承优先级）
        List<DemandOrder> bomBuckets = new ArrayList<>();
        for (DemandOrder parentBucket : new ArrayList<>(bucketDemands)) {
            for (BomArc arc : bomArcs) {
                if (arc.getParent().equals(parentBucket.getItem())) {
                    Item child = arc.getChild();
                    int qty = parentBucket.getQuantity() * arc.getQuantityPerParent();
                    LocalDate childDue = parentBucket.getDueDate().minusDays(Math.max(0, child.getLeadTime()));
                    if (childDue.isBefore(slotStart)) childDue = slotStart;
                    if (childDue.isAfter(slotEnd)) childDue = slotEnd;
                    int childIdx = lastIndexForDateOrClamp(timeSlots, childDue);
                    // 子桶继承父桶的优先级
                    bomBuckets.add(new DemandOrder(child, qty, childDue, childIdx, parentBucket.getPriority()));
                }
            }
        }
        // 合并子桶到主桶集合，并按 item+dueDate 再次合并
        bucketDemands.addAll(bomBuckets);
        bucketDemands = mergeByItemAndDueDate(bucketDemands, timeSlots);

        // 9) 修复：安全库存不再作为需求桶，而是作为约束在计分器中体现
        // 安全库存会在GlobalInventoryIncrementalScoreCalculator中作为软约束：
        // - 库存低于安全库存时会有惩罚
        // - 库存高于安全库存时有持有成本

        // 10) 修复：不再在求解前抵扣初始库存
        // 原因：应该让优化器根据全局约束自动决定如何使用初始库存，而非预先分配
        // 初始库存会在增量计分器中作为"时间槽0之前的库存"自动参与计算
        List<DemandOrder> finalDemands = new ArrayList<>(bucketDemands);

        // 11) Assignments
        List<ProductionAssignment> assignments = new ArrayList<>();
        long id = 1L;
        for (ProductionLine productionLine : productionLines) {
            for (TimeSlot slot : timeSlots) {
                assignments.add(new ProductionAssignment(id++, productionLine, slot));
            }
        }

        return new ProductionSchedule(
                routers, productionLines, timeSlots, bomArcs, inventories, finalDemands, assignments
        );
    }

    private  List<DemandOrder> mergeByItemAndDueDate(List<DemandOrder> list, List<TimeSlot> timeSlots) {
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (DemandOrder d : list) {
            String key = d.getItem().getCode() + "@" + d.getDueDate();
            agg.compute(key, (k, v) -> {
                if (v == null) {
                    v = new HashMap<>();
                    v.put("item", d.getItem());
                    v.put("qty", d.getQuantity());
                    v.put("priority", d.getPriority());
                } else {
                    int existQty = (int) v.get("qty");
                    int existPriority = (int) v.get("priority");
                    v.put("qty", existQty + d.getQuantity());
                    v.put("priority", Math.max(existPriority, d.getPriority()));
                }
                return v;
            });
        }
        List<DemandOrder> result = new ArrayList<>();
        for (Map<String, Object> data : agg.values()) {
            Item item = (Item) data.get("item");
            int qty = (int) data.get("qty");
            int priority = (int) data.get("priority");
            LocalDate due = item.getLeadTime() >= 0 ? item.getLeadTime() > 0 ? 
                LocalDate.now().plusDays(item.getLeadTime()) : LocalDate.now() : LocalDate.now();
            
            // 从原始列表找到正确的due date
            for (DemandOrder d : list) {
                if (d.getItem().equals(item)) {
                    due = d.getDueDate();
                    break;
                }
            }
            
            int dueIdx = lastIndexForDateOrClamp(timeSlots, due);
            result.add(new DemandOrder(item, qty, due, dueIdx, priority));
        }
        return result;
    }

    private List<TimeSlot> generateTimeSlots(LocalDate start, LocalDate end, int workStart, int workEnd) {
        List<TimeSlot> slots = new ArrayList<>();
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            for (int h = workStart; h <= workEnd; h++) {
                slots.add(new TimeSlot(d, h, idx++));
            }
        }
        return slots;
    }

    private int lastIndexForDateOrClamp(List<TimeSlot> slots, LocalDate date) {
        OptionalInt opt = slots.stream().filter(t -> t.getDate().equals(date)).mapToInt(TimeSlot::getIndex).max();
        if (opt.isPresent()) return opt.getAsInt();
        if (slots.isEmpty()) return 0;
        LocalDate minDate = slots.get(0).getDate();
        LocalDate maxDate = slots.get(slots.size() - 1).getDate();
        LocalDate target = date.isBefore(minDate) ? minDate : maxDate;
        return slots.stream().filter(t -> t.getDate().equals(target)).mapToInt(TimeSlot::getIndex).max()
                .orElse(slots.get(slots.size() - 1).getIndex());
    }
}