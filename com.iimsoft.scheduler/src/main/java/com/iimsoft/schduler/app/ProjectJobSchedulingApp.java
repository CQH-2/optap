package com.iimsoft.schduler.app;

import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;
import com.iimsoft.schduler.domain.Schedule;
import com.iimsoft.schduler.persistence.ProjectJobSchedulingImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.iimsoft.schduler.domain.Allocation;
import com.iimsoft.schduler.domain.ExecutionMode;
import com.iimsoft.schduler.domain.InventoryEvent;
import com.iimsoft.schduler.domain.InventoryEventTime;
import com.iimsoft.schduler.domain.Item;
import com.iimsoft.schduler.domain.Job;
import com.iimsoft.schduler.domain.JobType;
import com.iimsoft.schduler.domain.Project;

/**
 * Demo runner:
 * - If an input file path is provided, load from TXT importer (original example).
 * - Otherwise, load a HARD-CODED JSON demo (Route-C inventory + cross-project supply/competition).
 */
public class ProjectJobSchedulingApp {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectJobSchedulingApp.class);


    public static void main(String[] args) {
        String inputFile = args.length > 0 ? args[0] : null;
        new ProjectJobSchedulingApp().solve(inputFile);
    }

    public void solve(String inputFile) {
        LOGGER.info("======================================");
        LOGGER.info("Project Job Scheduling Solver");
        LOGGER.info("======================================");

        Schedule problem = loadProblem(inputFile);
        if (problem == null) {
            LOGGER.error("Failed to load problem. Please provide a valid input file.");
            return;
        }

        LOGGER.info("Problem loaded: {} projects, {} jobs, {} allocations, {} items, {} inventoryEvents",
                problem.getProjectList() == null ? 0 : problem.getProjectList().size(),
                problem.getJobList() == null ? 0 : problem.getJobList().size(),
                problem.getAllocationList() == null ? 0 : problem.getAllocationList().size(),
                problem.getItemList() == null ? 0 : problem.getItemList().size(),
                problem.getInventoryEventList() == null ? 0 : problem.getInventoryEventList().size());

        SolverFactory solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(Schedule.class)
                .withEntityClasses(com.iimsoft.schduler.domain.Allocation.class)
                .withScoreDirectorFactory(new org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(com.iimsoft.schduler.score.FactoryInventoryIncrementalScoreCalculator.class))
                .withTerminationSpentLimit(Duration.ofSeconds(10)));

        Solver solver = solverFactory.buildSolver();

        LOGGER.info("Starting solver...");
        long startTime = System.currentTimeMillis();
        Schedule solution = (Schedule) solver.solve(problem);
        long endTime = System.currentTimeMillis();

        LOGGER.info("======================================");
        LOGGER.info("Solving completed in {} ms", (endTime - startTime));
        LOGGER.info("Score: {}", solution.getScore());
        LOGGER.info("======================================");

        printInventoryTimeline(solution);
    }

    private Schedule loadProblem(String inputFile) {
        if (inputFile == null || inputFile.isEmpty()) {
            LOGGER.info("No input file specified. Using HARD-CODED JSON demo data (DTO -> domain).");
            return loadHardcodedJsonDemo();
        }
        try {
            File file = new File(inputFile);
            if (!file.exists()) {
                LOGGER.error("Input file not found: {}", inputFile);
                return null;
            }
            ProjectJobSchedulingImporter importer = new ProjectJobSchedulingImporter();
            return importer.readSolution(file);
        } catch (Exception e) {
            LOGGER.error("Error loading problem from file: {}", inputFile, e);
            return null;
        }
    }

    // =====================================================================
    // Demo JSON (DTO) -> build domain objects manually (avoids JsonIdentityInfo)
    // =====================================================================

    /**
     * 为什么不用直接 ObjectMapper.readValue(json, Schedule.class)？
     * - 你的 domain 类上用了 @JsonIdentityInfo 解决“对象引用/循环引用”。
     * - 但 demo JSON 里使用了裸数字引用（例如 "project": 0, "jobList": [0,1,2]），
     *   Jackson 默认无法把裸数字解析为 Identity 引用，因此会抛 UnresolvedForwardReference。
     *
     * 解决方案：
     * - JSON 仍然硬编码，但只解析成 DTO（无引用、无 Identity）。
     * - 在 Java 里手工 new Project/Job/ExecutionMode/Allocation 并把引用关系连起来。
     */
    private Schedule loadHardcodedJsonDemo() {
        final String json = """
            {
              "projects": [
                { "id": 0, "releaseDate": 0, "criticalPathDuration": 10 },
                { "id": 1, "releaseDate": 0, "criticalPathDuration": 10 }
              ],
              "jobs": [
                { "id": 0, "projectId": 0, "jobType": "SOURCE",   "duration": 0, "successorJobIds": [1] },
                { "id": 1, "projectId": 0, "jobType": "STANDARD", "duration": 5, "successorJobIds": [2] },
                { "id": 2, "projectId": 0, "jobType": "SINK",     "duration": 0, "successorJobIds": [] },

                { "id": 3, "projectId": 1, "jobType": "SOURCE",   "duration": 0, "successorJobIds": [4] },
                { "id": 4, "projectId": 1, "jobType": "STANDARD", "duration": 3, "successorJobIds": [5] },
                { "id": 5, "projectId": 1, "jobType": "SINK",     "duration": 0, "successorJobIds": [] }
              ],
              "items": [
                { "id": 100, "code": "WIP_A", "initialStock": 0 }
              ],
              "inventoryEvents": [
                { "id": 200, "allocationId": 1, "itemId": 100, "quantity": 10,  "timePolicy": "END"   },
                { "id": 201, "allocationId": 4, "itemId": 100, "quantity": -7,  "timePolicy": "START" }
              ]
            }
            """;

        try {
            ObjectMapper mapper = new ObjectMapper();
            DemoDto dto = mapper.readValue(json, DemoDto.class);

            // -----------------------------
            // 1) Create Schedule + lists
            // -----------------------------
            Schedule schedule = new Schedule(0L);
            List<Project> projectList = new ArrayList<>();
            List<Job> jobList = new ArrayList<>();
            List<ExecutionMode> executionModeList = new ArrayList<>();
            List<Allocation> allocationList = new ArrayList<>();
            List<Item> itemList = new ArrayList<>();
            List<InventoryEvent> inventoryEventList = new ArrayList<>();

            schedule.setProjectList(projectList);
            schedule.setJobList(jobList);
            schedule.setExecutionModeList(executionModeList);
            schedule.setAllocationList(allocationList);
            schedule.setItemList(itemList);
            schedule.setInventoryEventList(inventoryEventList);

            schedule.setResourceList(new ArrayList<>());             // demo 不用资源
            schedule.setResourceRequirementList(new ArrayList<>());  // demo 不用资源需求

            // -----------------------------
            // 2) Projects
            // -----------------------------
            Map<Long, Project> projectById = new HashMap<>();
            for (DemoProject p : dto.projects) {
                Project project = new Project(p.id, p.releaseDate, p.criticalPathDuration);
                project.setLocalResourceList(new ArrayList<>());
                project.setJobList(new ArrayList<>());
                projectList.add(project);
                projectById.put(p.id, project);
            }

            // -----------------------------
            // 3) Jobs + ExecutionMode(单模式) + Project.jobList
            // -----------------------------
            Map<Long, Job> jobById = new HashMap<>();
            Map<Long, ExecutionMode> modeByJobId = new HashMap<>();

            for (DemoJob j : dto.jobs) {
                Project project = projectById.get(j.projectId);
                Job job = new Job(j.id, project);
                job.setJobType(JobType.valueOf(j.jobType));
                job.setExecutionModeList(new ArrayList<>());
                job.setSuccessorJobList(new ArrayList<>());

                // 每个 job 建一个 executionMode（demo 简化成单模式）
                ExecutionMode mode = new ExecutionMode(j.id, job);
                mode.setDuration(j.duration);
                mode.setResourceRequirementList(new ArrayList<>());
                job.getExecutionModeList().add(mode);

                jobList.add(job);
                executionModeList.add(mode);

                project.getJobList().add(job);

                jobById.put(j.id, job);
                modeByJobId.put(j.id, mode);
            }

            // 建 successors
            for (DemoJob j : dto.jobs) {
                Job job = jobById.get(j.id);
                for (Long succId : j.successorJobIds) {
                    job.getSuccessorJobList().add(jobById.get(succId));
                }
            }

            // -----------------------------
            // 4) Allocations (一个 job 一个 allocation)
            // -----------------------------
            Map<Job, Allocation> allocByJob = new HashMap<>();
            Map<Long, Allocation> allocById = new HashMap<>();
            Map<Project, Allocation> sourceByProject = new HashMap<>();
            Map<Project, Allocation> sinkByProject = new HashMap<>();

            for (Job job : jobList) {
                Allocation a = new Allocation(job.getId(), job);
                a.setPredecessorAllocationList(new ArrayList<>());
                a.setSuccessorAllocationList(new ArrayList<>());
                // 初始影子变量，保证 start/end 有值
                a.setPredecessorsDoneDate(job.getProject().getReleaseDate());

                if (job.getJobType() == JobType.SOURCE || job.getJobType() == JobType.SINK) {
                    a.setDelay(0);
                    a.setExecutionMode((ExecutionMode) job.getExecutionModeList().get(0));
                }
                allocationList.add(a);
                allocByJob.put(job, a);
                allocById.put(a.getId(), a);

                if (job.getJobType() == JobType.SOURCE) {
                    sourceByProject.put(job.getProject(), a);
                } else if (job.getJobType() == JobType.SINK) {
                    sinkByProject.put(job.getProject(), a);
                }
            }

            // 连 predecessor/successor allocation
            for (Allocation a : allocationList) {
                Job job = a.getJob();
                a.setSourceAllocation(sourceByProject.get(job.getProject()));
                a.setSinkAllocation(sinkByProject.get(job.getProject()));
                for (Object succObj : job.getSuccessorJobList()) {
                    Job succJob = (Job) succObj;
                    Allocation succAlloc = allocByJob.get(succJob);
                    a.getSuccessorAllocationList().add(succAlloc);
                    succAlloc.getPredecessorAllocationList().add(a);
                }
            }
            // 给 source 的后继们刷新 predecessorsDoneDate
            for (Allocation source : sourceByProject.values()) {
                for (Object succAllocObj : source.getSuccessorAllocationList()) {
                    Allocation succAlloc = (Allocation) succAllocObj;
                    succAlloc.setPredecessorsDoneDate(source.getEndDate());
                }
            }

            // -----------------------------
            // 5) Items
            // -----------------------------
            Map<Long, Item> itemById = new HashMap<>();
            for (DemoItem i : dto.items) {
                Item item = new Item(i.id, i.code, i.initialStock);
                itemList.add(item);
                itemById.put(i.id, item);
            }

            // -----------------------------
            // 6) Inventory events (绑定 allocation 与 item)
            // -----------------------------
            for (DemoInventoryEvent e : dto.inventoryEvents) {
                Allocation alloc = allocById.get(e.allocationId);
                Item item = itemById.get(e.itemId);
                InventoryEvent event = new InventoryEvent(e.id, alloc, item, e.quantity, InventoryEventTime.valueOf(e.timePolicy));
                inventoryEventList.add(event);
            }

            return schedule;
        } catch (Exception e) {
            LOGGER.error("Failed to parse hard-coded JSON demo (DTO).", e);
            return null;
        }
    }

    // -----------------------------
    // DTO classes (no references)
    // -----------------------------
    public static class DemoDto {
        public List<DemoProject> projects;
        public List<DemoJob> jobs;
        public List<DemoItem> items;
        public List<DemoInventoryEvent> inventoryEvents;
    }

    public static class DemoProject {
        public long id;
        public int releaseDate;
        public int criticalPathDuration;
    }

    public static class DemoJob {
        public long id;
        public long projectId;
        public String jobType;
        public int duration;
        public List<Long> successorJobIds;
    }

    public static class DemoItem {
        public long id;
        public String code;
        public int initialStock;
    }

    public static class DemoInventoryEvent {
        public long id;
        public long allocationId;
        public long itemId;
        public int quantity;
        public String timePolicy;
    }

    private void printInventoryTimeline(Schedule solution) {
        if (solution.getItemList() == null || solution.getItemList().isEmpty()) {
            LOGGER.info("No items, skip inventory timeline.");
            return;
        }
        if (solution.getInventoryEventList() == null || solution.getInventoryEventList().isEmpty()) {
            LOGGER.info("No inventory events, skip inventory timeline.");
            return;
        }

        int horizon = calculateHorizon(solution);
        LOGGER.info("========== Inventory Timeline (0..{}) ==========", horizon);

        for (Object itemObj : solution.getItemList()) {
            com.iimsoft.schduler.domain.Item item = (com.iimsoft.schduler.domain.Item) itemObj;

            java.util.Map<Integer, Integer> deltaByDay = new java.util.HashMap<>();
            for (Object eObj : solution.getInventoryEventList()) {
                com.iimsoft.schduler.domain.InventoryEvent e = (com.iimsoft.schduler.domain.InventoryEvent) eObj;
                if (e.getItem() == null || e.getEventDate() == null) {
                    continue;
                }
                if (!e.getItem().equals(item)) {
                    continue;
                }
                deltaByDay.merge(e.getEventDate(), e.getQuantity(), Integer::sum);
            }

            LOGGER.info("---- Item {} (initialStock={}) events ----", item.getCode(), item.getInitialStock());
            deltaByDay.keySet().stream().sorted().forEach(day ->
                    LOGGER.info("  day {} : delta {}", day, deltaByDay.get(day)));

            LOGGER.info("---- Item {} balance curve ----", item.getCode());
            int balance = item.getInitialStock();
            for (int day = 0; day <= horizon; day++) {
                int delta = deltaByDay.getOrDefault(day, 0);
                balance += delta;
                LOGGER.info("  day {} : delta {}, balance {}", day, delta, balance);
            }
        }

        LOGGER.info("===============================================");
    }

    private int calculateHorizon(Schedule solution) {
        int max = 0;
        if (solution.getAllocationList() == null) {
            return max;
        }
        for (Object aObj : solution.getAllocationList()) {
            com.iimsoft.schduler.domain.Allocation a = (com.iimsoft.schduler.domain.Allocation) aObj;
            Integer end = a.getEndDate();
            if (end != null && end > max) {
                max = end;
            }
        }
        return max;
    }
}