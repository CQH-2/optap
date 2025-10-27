package com.iimsoft.scheduler.service;

import com.iimsoft.scheduler.domain.ProductionAssignment;
import com.iimsoft.scheduler.domain.ProductionSchedule;
import com.iimsoft.scheduler.domain.TimeSlot;
import com.iimsoft.scheduler.domain.Item;
import com.iimsoft.scheduler.domain.ProductionLine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class IOService {

    /**
     * 优化版CSV导出：
     * 按生产线、物料、日期分组，相邻时间段（同一物料）合并为一个区间，展示“起止小时”。
     * 输出表头：生产线,物料,日期,开始时间,结束时间,总数量
     */
    public static void exportScheduleToCsv(ProductionSchedule solution, String csvPath) throws IOException {
        File file = new File(csvPath);
        if (!file.isAbsolute()) {
            String cwd = System.getProperty("user.dir");
            file = new File(cwd, csvPath);
        }
        if (file.exists()) {
            if (file.isDirectory()) {
                throw new IOException("目标路径是目录: " + file.getAbsolutePath());
            }
            if (!file.delete()) {
                throw new IOException("无法删除文件: " + file.getAbsolutePath());
            }
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录: " + parent.getAbsolutePath());
            }
            if (!file.createNewFile()) {
                if (!file.exists()) {
                    throw new IOException("无法创建文件: " + file.getAbsolutePath());
                }
            }
        }
        // 建议用 UTF-8
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "GBK"))) {
            String sep = System.lineSeparator();
            // CSV表头
            writer.write("生产线,物料,日期,开始时间,结束时间,总数量");
            writer.write(sep);

            // 过滤掉空闲格子
            List<ProductionAssignment> validAssignments = solution.getAssignmentList().stream()
                    .filter(a -> a.getRouter() != null && a.getProducedItem() != null && a.getProducedQuantity() > 0)
                    .sorted(Comparator.comparing((ProductionAssignment a) -> a.getLine().getCode())
                            .thenComparing(a -> a.getProducedItem().getCode())
                            .thenComparing(a -> a.getTimeSlot().getDate())
                            .thenComparingInt(a -> a.getTimeSlot().getHour()))
                    .collect(Collectors.toList());

            // 分组：生产线 → 物料 → 日期 → 时间升序
            Map<ProductionLine, Map<Item, Map<LocalDate, List<ProductionAssignment>>>> grouped =
                    validAssignments.stream()
                            .collect(Collectors.groupingBy(
                                    ProductionAssignment::getLine,
                                    LinkedHashMap::new,
                                    Collectors.groupingBy(
                                            ProductionAssignment::getProducedItem,
                                            LinkedHashMap::new,
                                            Collectors.groupingBy(
                                                    a -> a.getTimeSlot().getDate(),
                                                    LinkedHashMap::new,
                                                    Collectors.toList()
                                            )
                                    )
                            ));

            for (var lineEntry : grouped.entrySet()) {
                ProductionLine line = lineEntry.getKey();
                for (var itemEntry : lineEntry.getValue().entrySet()) {
                    Item item = itemEntry.getKey();
                    for (var dateEntry : itemEntry.getValue().entrySet()) {
                        LocalDate date = dateEntry.getKey();
                        List<ProductionAssignment> assignments = dateEntry.getValue();
                        // 按小时升序
                        assignments = assignments.stream()
                                .sorted(Comparator.comparingInt(a -> a.getTimeSlot().getHour()))
                                .collect(Collectors.toList());

                        // 合并相邻时间段
                        int n = assignments.size();
                        int i = 0;
                        while (i < n) {
                            ProductionAssignment start = assignments.get(i);
                            int startHour = start.getTimeSlot().getHour();
                            int endHour = startHour;
                            int totalQty = start.getProducedQuantity();

                            int j = i + 1;
                            while (j < n) {
                                ProductionAssignment cur = assignments.get(j);
                                int curHour = cur.getTimeSlot().getHour();
                                if (curHour == endHour + 1) {
                                    // 连续
                                    endHour = curHour;
                                    totalQty += cur.getProducedQuantity();
                                    j++;
                                } else {
                                    break;
                                }
                            }
                            // 写一行
                            writer.write(String.join(",",
                                    line.getCode(),
                                    item.getCode(),
                                    date.toString(),
                                    String.format("%02d:00", startHour),
                                    String.format("%02d:00", endHour + 1), // 结束时间为下一个小时
                                    String.valueOf(totalQty)
                            ));
                            writer.write(sep);
                            i = j;
                        }
                    }
                }
            }
            writer.flush();
        }
    }
}