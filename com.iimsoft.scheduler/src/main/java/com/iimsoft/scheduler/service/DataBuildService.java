package com.iimsoft.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.dto.ImportDTOs;

import java.io.File;
import java.time.LocalDate;
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

        // 3) Routers
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