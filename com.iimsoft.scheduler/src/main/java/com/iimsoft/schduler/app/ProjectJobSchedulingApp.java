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

        LOGGER.info("Problem loaded: {} projects, {} jobs, {} allocations",
                problem.getProjectList().size(),
                problem.getJobList().size(),
                problem.getAllocationList().size());

        // Create solver with incremental score calculator
        SolverFactory<Schedule> solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(Schedule.class)
                .withEntityClasses(com.iimsoft.schduler.domain.Allocation.class)
                .withScoreDirectorFactory(new org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig()
                        .withIncrementalScoreCalculatorClass(com.iimsoft.schduler.score.FactoryInventoryIncrementalScoreCalculator.class))
                .withTerminationSpentLimit(Duration.ofMinutes(5)));

        Solver<Schedule> solver = solverFactory.buildSolver();

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
                    LOGGER.info("  Job {} - Mode: {}, Delay: {}, Start: {}, End: {}",
                            allocation.getJob().getId(),
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
            LOGGER.info("No input file specified. Using default test data.");
            // You can create a simple test problem here or load from resources
            return null;
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
}
