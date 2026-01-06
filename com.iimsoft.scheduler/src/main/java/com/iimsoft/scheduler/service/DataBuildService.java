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
            // 先按 item+dueDate 合并（同日同物料合并为一个桶）
            Map<String, Integer> agg = new LinkedHashMap<>();
            for (ImportDTOs.DemandDTO dmd : dto.demands) {
                Item it = itemMap.get(dmd.item);
                if (it == null) continue;
                LocalDate dueDate = LocalDate.parse(dmd.dueDate);
                String key = it.getCode() + "@" + dueDate;
                agg.merge(key, dmd.quantity, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : agg.entrySet()) {
                String[] parts = e.getKey().split("@", 2);
                Item it = itemMap.get(parts[0]);
                LocalDate due = LocalDate.parse(parts[1]);
                int dueIdx = lastIndexForDateOrClamp(timeSlots, due);
                bucketDemands.add(new DemandOrder(it, e.getValue(), due, dueIdx));
            }
        }

        // 8) BOM 分解：父桶 -> 子桶（前置 leadTime，超出时间窗 clamp）
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
                    bomBuckets.add(new DemandOrder(child, qty, childDue, childIdx));
                }
            }
        }
        // 合并子桶到主桶集合，并按 item+dueDate 再次合并
        bucketDemands.addAll(bomBuckets);
        bucketDemands = mergeByItemAndDueDate(bucketDemands, timeSlots);

        // 9) 安全库存作为额外桶（放在时间窗最后一天）
        if (!safetyStockMap.isEmpty()) {
            LocalDate endDate = timeSlots.isEmpty() ? slotEnd : timeSlots.get(timeSlots.size() - 1).getDate();
            int endIdx = lastIndexForDateOrClamp(timeSlots, endDate);
            for (Map.Entry<Item, Integer> e : safetyStockMap.entrySet()) {
                int safety = e.getValue() == null ? 0 : e.getValue();
                if (safety > 0) {
                    bucketDemands.add(new DemandOrder(e.getKey(), safety, endDate, endIdx));
                }
            }
        }
        // 再合并一下（以免安全库存和同日需求同日同物料叠加）
        bucketDemands = mergeByItemAndDueDate(bucketDemands, timeSlots);

        // 10) 初始库存按“先到期先抵扣”在各桶间分摊
        List<DemandOrder> finalDemands = new ArrayList<>();
        Map<Item, List<DemandOrder>> byItem = bucketDemands.stream()
                .collect(Collectors.groupingBy(DemandOrder::getItem, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Item, List<DemandOrder>> e : byItem.entrySet()) {
            Item item = e.getKey();
            List<DemandOrder> buckets = new ArrayList<>(e.getValue());
            buckets.sort(Comparator.comparing(DemandOrder::getDueDate));
            int onHand = initialOnHandMap.getOrDefault(item, 0);
            for (DemandOrder b : buckets) {
                if (onHand > 0) {
                    int consume = Math.min(onHand, b.getQuantity());
                    b.setQuantity(b.getQuantity() - consume);
                    onHand -= consume;
                }
                if (b.getQuantity() > 0) {
                    finalDemands.add(b);
                }
            }
        }

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
        Map<String, Integer> agg = new LinkedHashMap<>();
        for (DemandOrder d : list) {
            String key = d.getItem().getCode() + "@" + d.getDueDate();
            agg.merge(key, d.getQuantity(), Integer::sum);
        }
        List<DemandOrder> result = new ArrayList<>();
        for (Map.Entry<String, Integer> e : agg.entrySet()) {
            String[] parts = e.getKey().split("@", 2);
            String code = parts[0];
            LocalDate due = LocalDate.parse(parts[1]);
            Item item = list.stream().filter(x -> x.getItem().getCode().equals(code)).findFirst().get().getItem();
            int dueIdx = lastIndexForDateOrClamp(timeSlots, due);
            result.add(new DemandOrder(item, e.getValue(), due, dueIdx));
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