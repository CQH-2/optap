package com.iimsoft.scheduler.dto;

import java.util.List;

public class ImportDTOs {
    public static class Root {
        public List<ItemDTO> items;
        public List<BomArcDTO> bomArcs;
        public List<RouterDTO> routers;
        public List<LineDTO> lines;
        public List<InventoryDTO> inventories;
        public List<DemandDTO> demands;
    }
    public static class ItemDTO { public String code, name, itemType; public int leadTime; }
    public static class BomArcDTO { public String parent, child; public int quantityPerParent; }
    public static class RouterDTO { 
        public String code, item; 
        public int speedPerHour, setupTimeHours, minBatchSize; 
        public List<String> predecessors; // 前置工序代码列表
    }
    public static class LineDTO { public String code; public List<String> supportedRouters; }
    public static class InventoryDTO { public String item; public int initialOnHand, safetyStock; }
    public static class DemandDTO { public String item; public int quantity, priority; public String dueDate; }
    public static class ConfigDTO { public int overTolerance; } // 超产容忍度（百分比），默认3
}