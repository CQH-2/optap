package com.iimsoft.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.dto.ImportDTOs;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 将 JSON 数据封装为 ProductionSchedule 的服务。
 * - 支持直接传入 Root DTO
 * - 也支持传入 JSON 文件路径（自动读取）
 * - 自动生成时间槽、分解 BOM 子件需求、叠加安全库存、扣减初始库存、生成 Assignment
 *
 * 重要改动（保留“交付桶”）：
 * - 需求不再按物料聚合为单条，而是“保留每条需求为一个桶”（同物料不同到期日的多条需求分别为不同桶）
 * - BOM分解也按桶生成对应的子件桶（到期日前移 child.leadTime 天）
 * - 初始库存按“同物料的桶按到期早晚排序，从前往后逐桶扣减”
 * - 安全库存作为末尾的一个桶（截止为时间窗最后一天）
 */
public class DataBuildService {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 从文件读取 JSON，并自动推断时间窗，构建 ProductionSchedule。
     * 时间窗规则：
     * - workTime: 08:00 - 19:00
     * - slotStart: 最早需求日 - max(所有物料的 leadTime)
     * - slotEnd: 最晚需求日
     */
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

        LocalDate slotStart = earliestDue.minusDays(3);
        LocalDate slotEnd = latestDue;

        return buildSchedule(dto, slotStart, slotEnd, workStart, workEnd);
    }

    /**
     * 从文件读取 JSON，使用用户指定的时间窗和工作时间，构建 ProductionSchedule。
     */
    public ProductionSchedule buildScheduleFromFile(String jsonPath,
                                                    LocalDate slotStart, LocalDate slotEnd,
                                                    int workStart, int workEnd) throws Exception {
        ImportDTOs.Root dto = mapper.readValue(new File(jsonPath), ImportDTOs.Root.class);
        return buildSchedule(dto, slotStart, slotEnd, workStart, workEnd);
    }

    /**
     * 直接传入已解析的 Root DTO，使用用户指定的时间窗和工作时间，构建 ProductionSchedule。
     * 关键点：保留“桶”，不再聚合为每物料一条需求。
     */
    public ProductionSchedule buildSchedule(ImportDTOs.Root dto,
                                            LocalDate slotStart, LocalDate slotEnd,
                                            int workStart, int workEnd) {
        // 1) Items
        Map<String, Item> itemMap = new LinkedHashMap<>();
        if (dto.items != null) {
            for (ImportDTOs.ItemDTO it : dto.items) {
                itemMap.put(it.code, new Item(it.code, it.name, it.leadTime));
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
                    routerMap.put(r.code, new Router(r.code, item, r.speedPerHour));
                }
            }
        }
        List<Router> routers = new ArrayList<>(routerMap.values());

        // 4) Lines
        List<ProductionLine> lines = new ArrayList<>();
        if (dto.lines != null) {
            for (ImportDTOs.LineDTO l : dto.lines) {
                ProductionLine line = new ProductionLine(l.code);
                List<Router> supported = l.supportedRouters == null
                        ? List.of()
                        : l.supportedRouters.stream()
                        .map(routerMap::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                line.setSupportedRouters(supported);
                lines.add(line);
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
        // 便捷 map：Item -> 初始在库 / 安全库存
        Map<Item, Integer> initialOnHandMap = inventories.stream()
                .collect(Collectors.toMap(ItemInventory::getItem, ItemInventory::getInitialOnHand));
        Map<Item, Integer> safetyStockMap = inventories.stream()
                .collect(Collectors.toMap(ItemInventory::getItem, ItemInventory::getSafetyStock));

        // 6) TimeSlots
        List<TimeSlot> timeSlots = generateTimeSlots(slotStart, slotEnd, workStart, workEnd);

        // 7) 原始需求（DTO -> DemandOrder）作为“首批桶”
        List<DemandOrder> baseDemands = new ArrayList<>();
        if (dto.demands != null) {
            for (ImportDTOs.DemandDTO dmd : dto.demands) {
                Item it = itemMap.get(dmd.item);
                if (it == null) continue;
                LocalDate dueDate = LocalDate.parse(dmd.dueDate);
                int dueIdx = lastIndexForDateOrClamp(timeSlots, dueDate);
                baseDemands.add(new DemandOrder(it, dmd.quantity, dueDate, dueIdx));
            }
        }

        // 8) 桶化需求（保留每条桶，不聚合）：
        //    - 从 baseDemands 出发，按桶生成父件需求
        //    - BOM 一层分解：为每个桶生成子件桶，按 child.leadTime 向前平移到期
        //    - 将安全库存作为末尾的“安全库存桶”
        //    - 最后按物料、按到期顺序分配初始在库，逐桶冲减
        List<DemandOrder> demandBuckets = new ArrayList<>();
// 8.1 父件桶：直接加入
        demandBuckets.addAll(baseDemands);
// 8.2 递归分解BOM桶
        for (DemandOrder parentBucket : baseDemands) {
            decomposeBucket(parentBucket, bomArcs, demandBuckets, itemMap, slotStart, slotEnd, timeSlots);
        }

        // 8.3 安全库存桶：每个物料一个安全库存桶，截止设为时间窗最后一天
        LocalDate endDate = timeSlots.isEmpty() ? slotEnd : timeSlots.get(timeSlots.size() - 1).getDate();
        int endIdx = lastIndexForDateOrClamp(timeSlots, endDate);
        for (Map.Entry<Item, Integer> e : safetyStockMap.entrySet()) {
            Item item = e.getKey();
            int safety = e.getValue() == null ? 0 : e.getValue();
            if (safety > 0) {
                demandBuckets.add(new DemandOrder(item, safety, endDate, endIdx));
            }
        }

        // 8.4 初始库存冲减：同物料的桶按到期升序逐桶冲减
        List<DemandOrder> finalDemands = new ArrayList<>();
        Map<Item, List<DemandOrder>> bucketsByItem = demandBuckets.stream()
                .collect(Collectors.groupingBy(DemandOrder::getItem, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Item, List<DemandOrder>> e : bucketsByItem.entrySet()) {
            Item item = e.getKey();
            List<DemandOrder> buckets = e.getValue().stream()
                    .sorted(Comparator.comparingInt(DemandOrder::getDueTimeSlotIndex)) // 到期越早优先
                    .collect(Collectors.toList());
            int remainingInitial = initialOnHandMap.getOrDefault(item, 0);

            for (DemandOrder bucket : buckets) {
                int qty = bucket.getQuantity();
                if (remainingInitial > 0) {
                    int used = Math.min(qty, remainingInitial);
                    qty -= used;
                    remainingInitial -= used;
                }
                if (qty > 0) {
                    finalDemands.add(new DemandOrder(item, qty, bucket.getDueDate(), bucket.getDueTimeSlotIndex()));
                }
            }
        }

        // 9) Assignments：每条产线 * 每个时间槽
        List<ProductionAssignment> assignments = new ArrayList<>();
        long id = 1L;
        for (ProductionLine line : lines) {
            for (TimeSlot slot : timeSlots) {
                assignments.add(new ProductionAssignment(id++, line, slot));
            }
        }

        // 10) 组装 ProductionSchedule
        ProductionSchedule schedule = new ProductionSchedule(
                routers,
                lines,
                timeSlots,
                bomArcs,
                inventories,
                finalDemands,
                assignments
        );
        return schedule;
    }

    // 生成时间槽（包含起止日，按小时粒度，含起止小时）
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

    // 获取某日期当天最后一个时间槽的 index；若不在时间窗内则夹到最近边界
    private int lastIndexForDateOrClamp(List<TimeSlot> slots, LocalDate date) {
        OptionalInt opt = slots.stream()
                .filter(t -> t.getDate().equals(date))
                .mapToInt(TimeSlot::getIndex)
                .max();
        if (opt.isPresent()) return opt.getAsInt();

        if (slots.isEmpty()) return 0;
        LocalDate minDate = slots.get(0).getDate();
        LocalDate maxDate = slots.get(slots.size() - 1).getDate();

        LocalDate target = date.isBefore(minDate) ? minDate : maxDate;
        return slots.stream()
                .filter(t -> t.getDate().equals(target))
                .mapToInt(TimeSlot::getIndex)
                .max()
                .orElse(slots.get(slots.size() - 1).getIndex());
    }

    // 递归分解每个桶
    private void decomposeBucket(DemandOrder parentBucket, List<BomArc> bomArcs, List<DemandOrder> buckets,
                                 Map<String, Item> itemMap, LocalDate slotStart, LocalDate slotEnd, List<TimeSlot> timeSlots) {
        for (BomArc arc : bomArcs) {
            if (!arc.getParent().equals(parentBucket.getItem())) continue;
            Item child = arc.getChild();
            int childNeed = parentBucket.getQuantity() * arc.getQuantityPerParent();
            int lead = child.getLeadTime();

            LocalDate childDueDate = parentBucket.getDueDate().minusDays(lead);
            if (childDueDate.isBefore(slotStart)) childDueDate = slotStart;
            if (childDueDate.isAfter(slotEnd)) childDueDate = slotEnd;
            int childDueIdx = lastIndexForDateOrClamp(timeSlots, childDueDate);

            DemandOrder childBucket = new DemandOrder(child, childNeed, childDueDate, childDueIdx);
            buckets.add(childBucket);
            // 递归分解子件桶
            decomposeBucket(childBucket, bomArcs, buckets, itemMap, slotStart, slotEnd, timeSlots);
        }
    }
}