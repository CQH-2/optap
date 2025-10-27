package com.iimsoft.scheduler.solver;

import com.iimsoft.scheduler.domain.BomArc;
import com.iimsoft.scheduler.domain.TimeSlot;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class InventoryBalanceTuple {
    TimeSlot t;
    BomArc arc;
    int childSum;
    int parentSum;
}
