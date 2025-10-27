package com.iimsoft.scheduler.service;

import com.iimsoft.scheduler.domain.ProductionAssignment;
import com.iimsoft.scheduler.domain.ProductionSchedule;
import com.iimsoft.scheduler.domain.TimeSlot;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class IOService {


    public static void exportScheduleToCsv(ProductionSchedule solution, String csvPath) throws IOException {
        File file = new File(csvPath);
        if (!file.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            file = new File(cwd, csvPath);
        }
        // 建议用 OutputStreamWriter 并指定 UTF-8
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "GBK"))) {
            String sep = System.lineSeparator();
            // CSV表头
            writer.write("生产线,日期,时间,时间槽,工艺,物料,数量");
            writer.write(sep);
            for (ProductionAssignment a : solution.getAssignmentList()) {
                String line = a.getLine() != null ? a.getLine().getCode() : "";
                TimeSlot ts = a.getTimeSlot();
                String date = ts != null && ts.getDate() != null ? ts.getDate().toString() : "";
                String hour = ts != null ? String.valueOf(ts.getHour()) : "";
                String idx = ts != null ? String.valueOf(ts.getIndex()) : "";
                String router = a.getRouter() != null ? a.getRouter().getCode() : "";
                String item = a.getProducedItem() != null ? a.getProducedItem().getCode() : "";
                String qty = String.valueOf(a.getProducedQuantity());
                writer.write(String.join(",", line, date, hour, idx, router, item, qty));
                writer.write(sep);
            }
            writer.flush();
        }
    }
}
