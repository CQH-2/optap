package com.iimsoft.scheduler.domain;

/**
 * 链式排产中的“停驻点”：
 * - 锚点：LineShiftSlot（链的起点，链头的 previousStandstill 指向它）
 * - 实体：TaskPart（链中间节点）
 *
 * getChainEndIndex():
 * - 对于 LineShiftSlot：返回该槽位的开始时间（链头从这里开工）
 * - 对于 TaskPart：返回该分片的结束时间（后继的开始时间）
 */
public interface Standstill {
    Line getLine();
    LineShiftSlot getSlot();
    Long getChainEndIndex();
}