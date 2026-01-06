package com.iimsoft.scheduler.domain;

/**
 * 物料类型枚举
 */
public enum ItemType {
    /**
     * 自制件：在工厂内部生产
     * leadTime 表示生产周期（天）
     */
    MANUFACTURED,
    
    /**
     * 采购件：从外部供应商采购
     * leadTime 表示采购前置期（天）
     */
    PURCHASED,
    
    /**
     * 通用类型：可以是自制或采购
     */
    GENERIC
}
