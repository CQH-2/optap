package com.iimsoft.schduler.service;

import com.iimsoft.schduler.api.dto.SolveRequest;
import com.iimsoft.schduler.api.dto.SolveResponse;
import com.iimsoft.schduler.calendar.WorkCalendar;
import com.iimsoft.schduler.domain.*;
import com.iimsoft.schduler.domain.resource.GlobalResource;
import com.iimsoft.schduler.domain.resource.Resource;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

public class SchedulingSolveService {

    public SolveResponse solve(SolveRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);

        // WorkCalendar 是静态全局缓存：为了避免并发请求互相污染，这里串行化 solve。
        // 后续如果要支持并发，需要把 WorkCalendar 改为“每次求解独立实例”或 ThreadLocal。
        synchronized (WorkCalendar.class) {
            // 1) 把请求里的 calendar 下发给 WorkCalendar
            WorkCalendar.loadFromRequestCalendar(
                    request.calendar.timelineStartDate,
                    request.calendar.shifts,
                    request.calendar.workDates
            );

            // 2) 构建 domain Schedule
            Schedule schedule = buildSchedule(request);

            // 3) 构建 solver 并求解
            int seconds = request.terminationSeconds == null ? 10 : request.terminationSeconds;
            if (seconds <= 0) {
                seconds = 10;
            }
            SolverFactory solverFactory = SolverFactory.create(new SolverConfig()
                    .withSolutionClass(Schedule.class)
                    .withEntityClasses(Allocation.class)
                    .withScoreDirectorFactory(new org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig()
                            .withIncrementalScoreCalculatorClass(com.iimsoft.schduler.score.FactoryInventoryIncrementalScoreCalculator.class))
                    .withTerminationSpentLimit(Duration.ofSeconds(seconds)));

            Solver solver = solverFactory.buildSolver();
            Schedule solution = (Schedule) solver.solve(schedule);

            // 4) 组装 response
            return buildResponse(request, solution);
        }
    }

    private static void validateRequest(SolveRequest request) {
        if (request.calendar == null) {
            throw new IllegalArgumentException("request.calendar 不能为空");
        }
        if (request.calendar.timelineStartDate == null || request.calendar.timelineStartDate.isBlank()) {
            throw new IllegalArgumentException("request.calendar.timelineStartDate 不能为空");
        }
        // 提前解析，确保格式正确
        LocalDate.parse(request.calendar.timelineStartDate.trim());

        if (request.calendar.shifts == null || request.calendar.shifts.isEmpty()) {
            throw new IllegalArgumentException("request.calendar.shifts 不能为空（否则没有任何工作小时）");
        }
        if (request.calendar.workDates == null || request.calendar.workDates.isEmpty()) {
            throw new IllegalArgumentException("request.calendar.workDates 不能为空（否则默认全不工作）");
        }

        if (request.projects == null || request.projects.isEmpty()) {
            throw new IllegalArgumentException("request.projects 不能为空");
        }
        if (request.jobs == null || request.jobs.isEmpty()) {
            throw new IllegalArgumentException("request.jobs 不能为空");
        }
    }

    private Schedule buildSchedule(SolveRequest dto) {
        Schedule schedule = new Schedule(0L);

        List<Project> projectList = new ArrayList<>();
        List<Job> jobList = new ArrayList<>();
        List<ExecutionMode> modeList = new ArrayList<>();
        List<Allocation> allocationList = new ArrayList<>();
        List<Item> itemList = new ArrayList<>();
        List<InventoryEvent> eventList = new ArrayList<>();
        List<Resource> resourceList = new ArrayList<>();
        List<ResourceRequirement> rrList = new ArrayList<>();

        schedule.setProjectList((List) projectList);
        schedule.setJobList((List) jobList);
        schedule.setExecutionModeList((List) modeList);
        schedule.setAllocationList((List) allocationList);
        schedule.setItemList((List) itemList);
        schedule.setInventoryEventList((List) eventList);
        schedule.setResourceList((List) resourceList);
        schedule.setResourceRequirementList((List) rrList);

        // projects
        Map<Long, Project> projectById = new HashMap<>();
        for (SolveRequest.ProjectDto p : dto.projects) {
            Project project = new Project(p.id, p.releaseHour, p.criticalPathHours);
            project.setLocalResourceList(new ArrayList<>());
            project.setJobList(new ArrayList<>());
            projectList.add(project);
            projectById.put(p.id, project);
        }

        // resources（先按 global 处理）
        Map<Long, Resource> resourceById = new HashMap<>();
        if (dto.resources != null) {
            for (SolveRequest.ResourceDto r : dto.resources) {
                GlobalResource gr = new GlobalResource(r.id, r.capacity);
                resourceList.add(gr);
                resourceById.put(r.id, gr);
            }
        }

        // jobs + modes（每个 job 先做单 mode）
        Map<Long, Job> jobById = new HashMap<>();
        Map<Long, ExecutionMode> modeByJobId = new HashMap<>();
        for (SolveRequest.JobDto j : dto.jobs) {
            Project project = projectById.get(j.projectId);
            Job job = new Job(j.id, project);
            job.setJobType(JobType.valueOf(j.jobType));
            job.setExecutionModeList(new ArrayList<>());
            job.setSuccessorJobList(new ArrayList<>());

            ExecutionMode mode = new ExecutionMode(j.id, job);
            mode.setDuration(j.durationWorkHours);
            mode.setResourceRequirementList(new ArrayList<>());
            ((List) job.getExecutionModeList()).add(mode);

            jobList.add(job);
            modeList.add(mode);
            ((List) project.getJobList()).add(job);

            jobById.put(j.id, job);
            modeByJobId.put(j.id, mode);
        }

        // successors
        for (SolveRequest.JobDto j : dto.jobs) {
            Job job = jobById.get(j.id);
            if (j.successorJobIds != null) {
                for (Long succId : j.successorJobIds) {
                    ((List) job.getSuccessorJobList()).add(jobById.get(succId));
                }
            }
        }

        // allocations
        Map<Job, Allocation> allocByJob = new HashMap<>();
        Map<Project, Allocation> sourceByProject = new HashMap<>();
        Map<Project, Allocation> sinkByProject = new HashMap<>();

        for (Job job : jobList) {
            Allocation a = new Allocation(job.getId(), job);
            a.setPredecessorAllocationList(new ArrayList<>());
            a.setSuccessorAllocationList(new ArrayList<>());
            a.setPredecessorsDoneDate(job.getProject().getReleaseDate());

            // SOURCE/SINK 固定
            if (job.getJobType() == JobType.SOURCE || job.getJobType() == JobType.SINK) {
                a.setDelay(0);
                a.setExecutionMode((ExecutionMode) ((List) job.getExecutionModeList()).get(0));
            }

            allocationList.add(a);
            allocByJob.put(job, a);

            if (job.getJobType() == JobType.SOURCE) sourceByProject.put(job.getProject(), a);
            if (job.getJobType() == JobType.SINK) sinkByProject.put(job.getProject(), a);
        }

        // link allocation graph
        for (Allocation a : allocationList) {
            Job job = a.getJob();
            a.setSourceAllocation(sourceByProject.get(job.getProject()));
            a.setSinkAllocation(sinkByProject.get(job.getProject()));
            for (Object succObj : job.getSuccessorJobList()) {
                Job succJob = (Job) succObj;
                Allocation succAlloc = allocByJob.get(succJob);
                ((List) a.getSuccessorAllocationList()).add(succAlloc);
                ((List) succAlloc.getPredecessorAllocationList()).add(a);
            }
        }

        // items
        Map<Long, Item> itemById = new HashMap<>();
        if (dto.items != null) {
            for (SolveRequest.ItemDto i : dto.items) {
                Item item = new Item(i.id, i.code, i.initialStock);
                itemList.add(item);
                itemById.put(i.id, item);
            }
        }

        // resource requirements: bind to mode + schedule list
        if (dto.resourceRequirements != null) {
            for (SolveRequest.ResourceRequirementDto rr : dto.resourceRequirements) {
                ExecutionMode mode = modeByJobId.get(rr.jobId);
                Resource res = resourceById.get(rr.resourceId);
                ResourceRequirement req = new ResourceRequirement(rr.id, mode, res, rr.requirement);
                ((List) mode.getResourceRequirementList()).add(req);
                rrList.add(req);
            }
        }

        // inventory events: bind to allocation via jobId
        if (dto.inventoryEvents != null) {
            for (SolveRequest.InventoryEventDto e : dto.inventoryEvents) {
                Allocation alloc = allocByJob.get(jobById.get(e.jobId));
                Item item = itemById.get(e.itemId);
                InventoryEvent event = new InventoryEvent(e.id, alloc, item, e.quantity, InventoryEventTime.valueOf(e.timePolicy));
                eventList.add(event);
            }
        }

        return schedule;
    }

    private SolveResponse buildResponse(SolveRequest request, Schedule solution) {
        SolveResponse resp = new SolveResponse();
        resp.score = solution.getScore() == null ? null : solution.getScore().toString();

        LocalDate startDate = LocalDate.parse(request.calendar.timelineStartDate);
        // allocations
        List<SolveResponse.AllocationResult> allocs = new ArrayList<>();
        for (Object o : solution.getAllocationList()) {
            Allocation a = (Allocation) o;

            SolveResponse.AllocationResult r = new SolveResponse.AllocationResult();
            r.projectId = a.getProject().getId();
            r.jobId = a.getJob().getId();
            r.jobType = a.getJobType().name();
            r.startHour = a.getStartDate() == null ? null : a.getStartDate().longValue();
            r.endHour = a.getEndDate() == null ? null : a.getEndDate().longValue();

            if (a.getStartDate() != null) {
                r.startDateTime = startDate.atStartOfDay().plusHours(a.getStartDate()).toString();
            }
            if (a.getEndDate() != null) {
                r.endDateTime = startDate.atStartOfDay().plusHours(a.getEndDate()).toString();
            }
            r.durationCalendarHours = (a.getStartDate() != null && a.getEndDate() != null) ? (a.getEndDate() - a.getStartDate()) : null;
            r.durationWorkHours = a.getExecutionMode() == null ? null : a.getExecutionMode().getDuration();

            allocs.add(r);
        }
        resp.allocations = allocs;

        // resource usages（按小时统计占用）
        resp.resourceUsages = buildResourceUsages(solution, startDate);

        // inventory timeline
        resp.inventoryTimelines = buildInventoryTimelines(solution, startDate);

        return resp;
    }

    private List<SolveResponse.ResourceUsage> buildResourceUsages(Schedule solution, LocalDate startDate) {
        // 简单实现：扫描所有 allocation 的工作小时，按资源累加
        Map<Long, Map<Integer, Integer>> used = new HashMap<>();
        Map<Long, Integer> cap = new HashMap<>();

        if (solution.getResourceList() != null) {
            for (Object ro : solution.getResourceList()) {
                Resource r = (Resource) ro;
                cap.put(r.getId(), r.getCapacity());
            }
        }

        for (Object ao : solution.getAllocationList()) {
            Allocation a = (Allocation) ao;
            if (a.getJobType() != JobType.STANDARD || a.getExecutionMode() == null) continue;
            if (a.getStartDate() == null || a.getEndDate() == null) continue;

            ExecutionMode mode = a.getExecutionMode();
            if (mode.getResourceRequirementList() == null) continue;

            for (Object rro : mode.getResourceRequirementList()) {
                ResourceRequirement rr = (ResourceRequirement) rro;
                Resource res = rr.getResource();
                if (res == null || !res.isRenewable()) continue;

                Map<Integer, Integer> perHour = used.computeIfAbsent(res.getId(), k -> new HashMap<>());
                for (int h = a.getStartDate(); h < a.getEndDate(); h++) {
                    if (!WorkCalendar.isWorkingHour(h)) continue;
                    perHour.merge(h, rr.getRequirement(), Integer::sum);
                }
            }
        }

        List<SolveResponse.ResourceUsage> out = new ArrayList<>();
        for (Map.Entry<Long, Map<Integer, Integer>> e : used.entrySet()) {
            long resourceId = e.getKey();
            int capacity = cap.getOrDefault(resourceId, 0);
            for (Map.Entry<Integer, Integer> hh : e.getValue().entrySet()) {
                SolveResponse.ResourceUsage ru = new SolveResponse.ResourceUsage();
                ru.resourceId = resourceId;
                ru.hour = hh.getKey();
                ru.used = hh.getValue();
                ru.capacity = capacity;
                ru.dateTime = startDate.atStartOfDay().plusHours(ru.hour).toString();
                out.add(ru);
            }
        }
        out.sort(Comparator.comparingLong((SolveResponse.ResourceUsage r) -> r.resourceId).thenComparingInt(r -> r.hour));
        return out;
    }

    private List<SolveResponse.ItemInventoryTimeline> buildInventoryTimelines(Schedule solution, LocalDate startDate) {
        if (solution.getItemList() == null) return List.of();

        // 先收集每个 item 的事件（按小时）
        Map<Item, List<InventoryEvent>> eventsByItem = new HashMap<>();
        if (solution.getInventoryEventList() != null) {
            for (Object eo : solution.getInventoryEventList()) {
                InventoryEvent e = (InventoryEvent) eo;
                if (e.getItem() == null || e.getEventDate() == null) continue;
                eventsByItem.computeIfAbsent(e.getItem(), k -> new ArrayList<>()).add(e);
            }
        }

        List<SolveResponse.ItemInventoryTimeline> out = new ArrayList<>();
        for (Object io : solution.getItemList()) {
            Item item = (Item) io;
            List<InventoryEvent> events = eventsByItem.getOrDefault(item, List.of());
            events.sort(Comparator.comparingInt(InventoryEvent::getEventDate));

            SolveResponse.ItemInventoryTimeline t = new SolveResponse.ItemInventoryTimeline();
            t.itemId = item.getId();
            t.itemCode = item.getCode();

            // events
            List<SolveResponse.InventoryEventAtTime> evOut = new ArrayList<>();
            for (InventoryEvent e : events) {
                SolveResponse.InventoryEventAtTime ee = new SolveResponse.InventoryEventAtTime();
                ee.hour = e.getEventDate();
                ee.dateTime = startDate.atStartOfDay().plusHours(ee.hour).toString();
                ee.quantity = e.getQuantity();
                ee.timePolicy = e.getTimePolicy().name();
                ee.jobId = e.getAllocation() == null ? -1 : e.getAllocation().getJob().getId();
                evOut.add(ee);
            }
            t.events = evOut;

            // balance curve（简单：在事件点上计算余额；如果你要每小时完整曲线也可以）
            int balance = item.getInitialStock();
            List<SolveResponse.InventoryBalancePoint> bp = new ArrayList<>();
            int lastHour = Integer.MIN_VALUE;
            for (SolveResponse.InventoryEventAtTime ee : evOut) {
                if (ee.hour != lastHour) {
                    SolveResponse.InventoryBalancePoint p = new SolveResponse.InventoryBalancePoint();
                    p.hour = ee.hour;
                    p.dateTime = ee.dateTime;
                    // 先不更新 balance，保持“事件发生前”的点也可加；这里选择事件后余额
                    balance += ee.quantity;
                    p.balance = balance;
                    bp.add(p);
                    lastHour = ee.hour;
                } else {
                    // 同一小时多个事件：追加到最后一个点
                    balance += ee.quantity;
                    bp.get(bp.size() - 1).balance = balance;
                }
            }
            t.balancePoints = bp;

            out.add(t);
        }
        return out;
    }
}