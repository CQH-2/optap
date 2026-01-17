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

/**
 * Demo runner:
 * - If an input file path is provided, load from TXT importer (original example).
 * - Otherwise, load a HARD-CODED JSON demo (Route-C inventory + cross-project supply/competition).
 */
public class ProjectJobSchedulingApp {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectJobSchedulingApp.class);

    public static final String SOLVER_CONFIG =
            "org/optaplanner/examples/projectjobscheduling/projectJobSchedulingSolverConfig.xml";

    public static void main(String[] args) {
        String inputFile = args.length > 0 ? args[0] : null;
        new ProjectJobSchedulingApp().solve(inputFile);
    }

    public void solve(String inputFile) {
        LOGGER.info("======================================");
        LOGGER.info("Project Job Scheduling Solver");
        LOGGER.info("======================================");

        // Load problem
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

        // Create solver with incremental score calculator
        SolverFactory solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(Schedule.class)
                .withEntityClasses(com.iimsoft.schduler.domain.Allocation.class)
                .withScoreDirectorFactory(new org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(com.iimsoft.schduler.score.FactoryInventoryIncrementalScoreCalculator.class))
                .withTerminationSpentLimit(Duration.ofSeconds(10)));

        Solver solver = solverFactory.buildSolver();

        // Solve
        LOGGER.info("Starting solver...");
        long startTime = System.currentTimeMillis();
        Schedule solution = solver.solve(problem);
        long endTime = System.currentTimeMillis();

        // Print results
        LOGGER.info("======================================");
        LOGGER.info("Solving completed in {} ms", (endTime - startTime));
        LOGGER.info("Score: {}", solution.getScore());
        LOGGER.info("======================================");

        // Print allocation details
        if (solution.getAllocationList() != null) {
            LOGGER.info("Allocations:");
            solution.getAllocationList().forEach(allocation -> {
                if (allocation.getExecutionMode() != null) {
                    LOGGER.info("  Project {} Job {} ({}) - Mode: {}, Delay: {}, Start: {}, End: {}",
                            allocation.getProject().getId(),
                            allocation.getJob().getId(),
                            allocation.getJobType(),
                            allocation.getExecutionMode().getId(),
                            allocation.getDelay(),
                            allocation.getStartDate(),
                            allocation.getEndDate());
                }
            });
        }
    }

    private Schedule loadProblem(String inputFile) {
        if (inputFile == null || inputFile.isEmpty()) {
            LOGGER.info("No input file specified. Using HARD-CODED JSON demo data.");
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

    /**
     * Hard-coded JSON demo.
     *
     * Design:
     * - Two projects (A,B).
     * - One shared item "WIP_A" with initialStock = 0.
     * - Project A has one STANDARD job that produces +10 WIP_A at END.
     * - Project B has one STANDARD job that consumes -7 WIP_A at START.
     *
     * Because inventory is global and shared, B must not start before A ends,
     * otherwise inventory would go negative => hard penalty.
     */
    private Schedule loadHardcodedJsonDemo() {
        // NOTE: This JSON uses Jackson @JsonIdentityInfo ids.
        // Each object includes an "id". References use the id value (number).
        //
        // Also note: This is a minimal demo; it keeps resources empty to focus on inventory.
        // If you want to include machine capacity too, add resource/resourceRequirement similarly.
        final String json = """
                {
                  "id": 0,
                  "projectList": [
                    { "id": 0, "releaseDate": 0, "criticalPathDuration": 10, "localResourceList": [], "jobList": [0, 1, 2] },
                    { "id": 1, "releaseDate": 0, "criticalPathDuration": 10, "localResourceList": [], "jobList": [3, 4, 5] }
                  ],
                  "jobList": [
                    { "id": 0, "project": 0, "jobType": "SOURCE", "executionModeList": [0], "successorJobList": [1] },
                    { "id": 1, "project": 0, "jobType": "STANDARD", "executionModeList": [1], "successorJobList": [2] },
                    { "id": 2, "project": 0, "jobType": "SINK", "executionModeList": [2], "successorJobList": [] },

                    { "id": 3, "project": 1, "jobType": "SOURCE", "executionModeList": [3], "successorJobList": [4] },
                    { "id": 4, "project": 1, "jobType": "STANDARD", "executionModeList": [4], "successorJobList": [5] },
                    { "id": 5, "project": 1, "jobType": "SINK", "executionModeList": [5], "successorJobList": [] }
                  ],
                  "executionModeList": [
                    { "id": 0, "job": 0, "duration": 0, "resourceRequirementList": [] },
                    { "id": 1, "job": 1, "duration": 5, "resourceRequirementList": [] },
                    { "id": 2, "job": 2, "duration": 0, "resourceRequirementList": [] },

                    { "id": 3, "job": 3, "duration": 0, "resourceRequirementList": [] },
                    { "id": 4, "job": 4, "duration": 3, "resourceRequirementList": [] },
                    { "id": 5, "job": 5, "duration": 0, "resourceRequirementList": [] }
                  ],
                  "resourceList": [],
                  "resourceRequirementList": [],

                  "itemList": [
                    { "id": 100, "code": "WIP_A", "initialStock": 0 }
                  ],
                  "inventoryEventList": [
                    { "id": 200, "allocation": 1, "item": 100, "quantity": 10, "timePolicy": "END" },
                    { "id": 201, "allocation": 4, "item": 100, "quantity": -7, "timePolicy": "START" }
                  ],

                  "allocationList": [
                    {
                      "id": 0, "job": 0,
                      "executionMode": 0, "delay": 0, "predecessorsDoneDate": 0,
                      "predecessorAllocationList": [], "successorAllocationList": [1]
                    },
                    {
                      "id": 1, "job": 1,
                      "executionMode": 1, "delay": 0, "predecessorsDoneDate": 0,
                      "predecessorAllocationList": [0], "successorAllocationList": [2]
                    },
                    {
                      "id": 2, "job": 2,
                      "executionMode": 2, "delay": 0, "predecessorsDoneDate": 0,
                      "predecessorAllocationList": [1], "successorAllocationList": []
                    },

                    {
                      "id": 3, "job": 3,
                      "executionMode": 3, "delay": 0, "predecessorsDoneDate": 0,
                      "predecessorAllocationList": [], "successorAllocationList": [4]
                    },
                    {
                      "id": 4, "job": 4,
                      "executionMode": 4, "delay": 0, "predecessorsDoneDate": 0,
                      "predecessorAllocationList": [3], "successorAllocationList": [5]
                    },
                    {
                      "id": 5, "job": 5,
                      "executionMode": 5, "delay": 0, "predecessorsDoneDate": 0,
                      "predecessorAllocationList": [4], "successorAllocationList": []
                    }
                  ],

                  "score": null
                }
                """;

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, Schedule.class);
        } catch (Exception e) {
            LOGGER.error("Failed to parse hard-coded JSON demo.", e);
            return null;
        }
    }
}