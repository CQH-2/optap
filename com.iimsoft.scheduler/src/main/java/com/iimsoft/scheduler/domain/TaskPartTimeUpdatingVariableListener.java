package com.iimsoft.scheduler.domain;

import com.iimsoft.scheduler.domain.LineShiftSlot;
import com.iimsoft.scheduler.domain.ProductionSchedule;
import com.iimsoft.scheduler.domain.TaskPart;
import org.optaplanner.core.api.domain.variable.VariableListener;
import org.optaplanner.core.impl.score.director.ScoreDirector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 根据 slot/router/indexInSlot 对同一槽位内的 TaskPart 排序，
 * 顺序累计 requiredMinutes，计算 startIndex/endIndex。
 */
@SuppressWarnings({"rawtypes","unchecked"})
public class TaskPartTimeUpdatingVariableListener implements VariableListener {

    @Override
    public void beforeEntityAdded(ScoreDirector scoreDirector, Object entity) {}

    @Override
    public void afterEntityAdded(ScoreDirector scoreDirector, Object entity) {
        recomputeAll(scoreDirector);
    }

    @Override
    public void beforeVariableChanged(ScoreDirector scoreDirector, Object entity) {}

    @Override
    public void afterVariableChanged(ScoreDirector scoreDirector, Object entity) {
        recomputeAll(scoreDirector);
    }

    @Override
    public void beforeEntityRemoved(ScoreDirector scoreDirector, Object entity) {}

    @Override
    public void afterEntityRemoved(ScoreDirector scoreDirector, Object entity) {}

    private void recomputeAll(ScoreDirector scoreDirector) {
        ProductionSchedule schedule = (ProductionSchedule) scoreDirector.getWorkingSolution();
        if (schedule == null || schedule.getTaskPartList() == null) return;

        // 先把全部分片的 start/end 置空，避免跨槽位残留
        for (TaskPart tp : schedule.getTaskPartList()) {
            setStartEnd(scoreDirector, tp, null, null);
        }

        // 按槽位分组
        Map<LineShiftSlot, List<TaskPart>> bySlot = schedule.getTaskPartList().stream()
                .filter(tp -> tp.getSlot() != null)
                .collect(Collectors.groupingBy(TaskPart::getSlot));

        for (Map.Entry<LineShiftSlot, List<TaskPart>> e : bySlot.entrySet()) {
            LineShiftSlot slot = e.getKey();
            List<TaskPart> list = e.getValue();

            // 仅对“router 与 indexInSlot 都有值”的分片进行排程
            List<TaskPart> schedulables = list.stream()
                    .filter(tp -> tp.getRouter() != null && tp.getIndexInSlot() != null)
                    .sorted(Comparator
                            .comparing(TaskPart::getIndexInSlot)
                            .thenComparing(TaskPart::getId, Comparator.nullsLast(Long::compareTo)))
                    .collect(Collectors.toList());

            long cursor = slot.getStartIndex();
            for (TaskPart tp : schedulables) {
                int dur = Math.max(0, tp.getRequiredMinutes());
                Long start = cursor;
                Long end = start + dur;
                setStartEnd(scoreDirector, tp, start, end);
                cursor = end;
            }
        }
    }

    private void setStartEnd(ScoreDirector scoreDirector, TaskPart tp, Long start, Long end) {
        if (!Objects.equals(tp.getStartIndex(), start)) {
            scoreDirector.beforeVariableChanged(tp, "startIndex");
            tp.setStartIndex(start);
            scoreDirector.afterVariableChanged(tp, "startIndex");
        }
        if (!Objects.equals(tp.getEndIndex(), end)) {
            scoreDirector.beforeVariableChanged(tp, "endIndex");
            tp.setEndIndex(end);
            scoreDirector.afterVariableChanged(tp, "endIndex");
        }
    }
}