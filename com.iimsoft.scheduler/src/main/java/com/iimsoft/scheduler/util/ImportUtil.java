package com.iimsoft.scheduler.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iimsoft.scheduler.domain.*;
import com.iimsoft.scheduler.dto.ImportDTOs;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class ImportUtil {

    // 生成时间槽（可调参数）
    public static List<TimeSlot> generateTimeSlots(LocalDate start, LocalDate end, int workStart, int workEnd) {
        List<TimeSlot> slots = new ArrayList<>();
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            for (int h = workStart; h <= workEnd; h++) {
                slots.add(new TimeSlot(d, h, idx++));
            }
        }
        return slots;
    }

    public static App.ExampleData loadExampleDataFromJson(String jsonPath, LocalDate slotStart, LocalDate slotEnd, int workStart, int workEnd) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ImportDTOs.Root dto = mapper.readValue(new File(jsonPath), ImportDTOs.Root.class);

        // 1. Items
        Map<String, Item> itemMap = new HashMap<>();
        for (ImportDTOs.ItemDTO it : dto.items) {
            itemMap.put(it.code, new Item(it.code, it.name, it.leadTime));
        }
        // 2. BOM
        List<BomArc> bomArcs = new ArrayList<>();
        for (ImportDTOs.BomArcDTO arc : dto.bomArcs) {
            bomArcs.add(new BomArc(itemMap.get(arc.parent), itemMap.get(arc.child), arc.quantityPerParent));
        }
        // 3. Routers
        Map<String, Router> routerMap = new HashMap<>();
        for (ImportDTOs.RouterDTO r : dto.routers) {
            Router router = new Router(r.code, itemMap.get(r.item), r.speedPerHour);
            routerMap.put(r.code, router);
        }
        // 4. Lines
        List<ProductionLine> lines = new ArrayList<>();
        for (ImportDTOs.LineDTO l : dto.lines) {
            ProductionLine line = new ProductionLine(l.code);
            line.setSupportedRouters(
                l.supportedRouters.stream().map(routerMap::get).collect(Collectors.toList()));
            lines.add(line);
        }
        // 5. Inventories
        List<ItemInventory> inventories = new ArrayList<>();
        for (ImportDTOs.InventoryDTO inv : dto.inventories) {
            ItemInventory ii = new ItemInventory(itemMap.get(inv.item), inv.initialOnHand);
            ii.setSafetyStock(inv.safetyStock);
            inventories.add(ii);
        }
        // 6. TimeSlots（自动生成）
        List<TimeSlot> slots = generateTimeSlots(slotStart, slotEnd, workStart, workEnd);

        // 7. Demands（自动分配dueTimeSlotIndex）
        List<DemandOrder> demands = new ArrayList<>();
        for (ImportDTOs.DemandDTO dmd : dto.demands) {
            Item item = itemMap.get(dmd.item);
            LocalDate dueDate = LocalDate.parse(dmd.dueDate);
            int dueIdx = slots.stream().filter(t -> t.getDate().equals(dueDate))
                    .mapToInt(TimeSlot::getIndex).max().orElse(slots.size() - 1);
            demands.add(new DemandOrder(item, dmd.quantity, dueDate, dueIdx));
        }

        // 8. Assignments
        List<ProductionAssignment> assignments = new ArrayList<>();
        long id = 1;
        for (ProductionLine line : lines) {
            for (TimeSlot slot : slots) {
                assignments.add(new ProductionAssignment(id++, line, slot));
            }
        }

        // 9. ExampleData组装
        App.ExampleData data = new App.ExampleData();
        data.items = new ArrayList<>(itemMap.values());
        data.bomArcs = bomArcs;
        data.routers = new ArrayList<>(routerMap.values());
        data.lines = lines;
        data.timeSlots = slots;
        data.inventories = inventories;
        data.demands = demands;
        data.assignments = assignments;
        return data;
    }
}