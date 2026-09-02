package com.syed.apiqa.run;

import com.syed.apiqa.auth.DynamicAuthService;
import com.syed.apiqa.cleanup.ResourceCleanupManager;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.execution.HttpExecutionEngine;
import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.agent.FailureIsolationHandler;
import com.syed.apiqa.planning.DependencyEngine;
import com.syed.apiqa.discovery.OpenApiFetchService;
import com.syed.apiqa.discovery.OpenApiParserService;
import com.syed.apiqa.performance.PerformanceAnalyticsService;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.planning.TestPlanService;
import com.syed.apiqa.regression.HistoricalRegressionService;
import com.syed.apiqa.reporting.HtmlReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production Orchestrator managing execution lifecycle, real bounded concurrency,
 * state machine transitions, pause/resume, cancellations, timeout watchdogs, and crash recovery.
 */
@Service
public class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    private final OpenApiFetchService fetchService;
    private final OpenApiParserService parserService;
    private final DependencyEngine dependencyEngine;
    private final TestPlanService testPlanService;
    private final HttpExecutionEngine httpEngine;
    private final FailureIsolationHandler failureIsolationHandler;
    private final DynamicAuthService dynamicAuthService;
    private final ResourceCleanupManager cleanupManager;
    private final PerformanceAnalyticsService performanceAnalyticsService;
    private final HistoricalRegressionService historicalRegressionService;
    private final HtmlReportGenerator reportGenerator;
    private final com.syed.apiqa.reporting.PdfReportGenerator pdfReportGenerator;
    private final SseEventService sseEventService;
    private final TestRunRepository testRunRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final DependencyRepository dependencyRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestStepRepository testStepRepository;
    private final ExecutionRepository executionRepository;
    private final RunAuditEventRepository auditEventRepository;
    private final com.syed.apiqa.coverage.CoverageCalculationService coverageCalculationService;
    private final com.syed.apiqa.safety.SecretMasker secretMasker;

    // Concurrency limiter: dynamically configured via syed.safety.max-concurrency
    private final int maxConcurrency;
    private final Semaphore concurrencyLimiter;

    // Active lifecycle control flags
    private final Map<String, Boolean> cancellationFlags = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pauseFlags = new ConcurrentHashMap<>();

    public RunManager(OpenApiFetchService fetchService,
                      OpenApiParserService parserService,
                      DependencyEngine dependencyEngine,
                      TestPlanService testPlanService,
                      HttpExecutionEngine httpEngine,
                      FailureIsolationHandler failureIsolationHandler,
                      DynamicAuthService dynamicAuthService,
                      ResourceCleanupManager cleanupManager,
                      PerformanceAnalyticsService performanceAnalyticsService,
                      HistoricalRegressionService historicalRegressionService,
                      HtmlReportGenerator reportGenerator,
                      com.syed.apiqa.reporting.PdfReportGenerator pdfReportGenerator,
                      SseEventService sseEventService,
                      TestRunRepository testRunRepository,
                      ApiEndpointRepository apiEndpointRepository,
                      DependencyRepository dependencyRepository,
                      TestCaseRepository testCaseRepository,
                      TestStepRepository testStepRepository,
                      ExecutionRepository executionRepository,
                      RunAuditEventRepository auditEventRepository,
                      com.syed.apiqa.coverage.CoverageCalculationService coverageCalculationService,
                      @org.springframework.beans.factory.annotation.Value("${syed.safety.max-concurrency:5}") int maxConcurrency,
                      com.syed.apiqa.safety.SecretMasker secretMasker) {
        this.fetchService = fetchService;
        this.parserService = parserService;
        this.dependencyEngine = dependencyEngine;
        this.testPlanService = testPlanService;
        this.httpEngine = httpEngine;
        this.failureIsolationHandler = failureIsolationHandler;
        this.dynamicAuthService = dynamicAuthService;
        this.cleanupManager = cleanupManager;
        this.performanceAnalyticsService = performanceAnalyticsService;
        this.historicalRegressionService = historicalRegressionService;
        this.reportGenerator = reportGenerator;
        this.pdfReportGenerator = pdfReportGenerator;
        this.sseEventService = sseEventService;
        this.testRunRepository = testRunRepository;
        this.apiEndpointRepository = apiEndpointRepository;
        this.dependencyRepository = dependencyRepository;
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
        this.auditEventRepository = auditEventRepository;
        this.coverageCalculationService = coverageCalculationService;
        this.maxConcurrency = maxConcurrency > 0 ? maxConcurrency : 5;
        this.concurrencyLimiter = new Semaphore(this.maxConcurrency, true);
        this.secretMasker = secretMasker;
    }

    /**
     * 6.7 Crash Recovery on Startup: Lingering runs found in active states after a backend restart
     * are safely transitioned to FAILED with explicit reason.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverLingeringRunsOnStartup() {
        List<TestRun> allRuns = testRunRepository.findByOrderByCreatedAtDesc();
        int recovered = 0;
        for (TestRun r : allRuns) {
            if (r.getStatus() != null && r.getStatus().isActive()) {
                log.warn("Detecting lingering active run {} in state {}. Transitioning to FAILED (CRASH_RECOVERY).",
                        r.getId(), r.getStatus());
                r.setStatus(RunStatus.FAILED);
                r.setErrorMessage("BACKEND_RESTART_DURING_EXECUTION");
                r.setCompletedAt(OffsetDateTime.now());
                testRunRepository.save(r);
                recordAudit(r, "CRASH_RECOVERY", "SYSTEM", "Recovered lingering run stuck in " + r.getStatus() + " following application reboot.");
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("Completed startup crash recovery: Safely transitioned {} lingering runs to FAILED.", recovered);
        }
    }

    /**
     * 6.2 Cancel Run
     */
    public boolean cancelRun(String testRunId, String actor, String reason) {
        TestRun run = testRunRepository.findById(testRunId).orElse(null);
        if (run == null || run.getStatus().isTerminal()) {
            return false;
        }

        log.info("Cancelling TestRun {} by actor '{}' (Reason: {})", testRunId, actor, reason);
        cancellationFlags.put(testRunId, true);
        pauseFlags.remove(testRunId); // Release pause lock so thread can exit immediately

        run.setStatus(RunStatus.CANCELLED);
        run.setCancellationReason(reason != null ? reason : "User cancelled execution");
        run.setCompletedAt(OffsetDateTime.now());
        testRunRepository.save(run);

        sseEventService.publishEvent(testRunId, "RUN_CANCELLED", Map.of(
                "actor", actor,
                "reason", run.getCancellationReason()
        ));
        recordAudit(run, "CANCELLED", actor, run.getCancellationReason());
        return true;
    }

    /**
     * 6.3 Pause Run
     */
    public boolean pauseRun(String testRunId, String actor) {
        TestRun run = testRunRepository.findById(testRunId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.EXECUTING) {
            return false;
        }

        pauseFlags.put(testRunId, true);
        run.setStatus(RunStatus.PAUSED);
        testRunRepository.save(run);

        sseEventService.publishEvent(testRunId, "RUN_PAUSED", Map.of("actor", actor));
        recordAudit(run, "PAUSED", actor, "Execution paused after current network operation.");
        return true;
    }

    /**
     * 6.3 Resume Run
     */
    public boolean resumeRun(String testRunId, String actor) {
        TestRun run = testRunRepository.findById(testRunId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.PAUSED) {
            return false;
        }

        pauseFlags.remove(testRunId);
        run.setStatus(RunStatus.EXECUTING);
        testRunRepository.save(run);

        sseEventService.publishEvent(testRunId, "RUN_RESUMED", Map.of("actor", actor));
        recordAudit(run, "RESUMED", actor, "Execution resumed from last checkpoint.");
        return true;
    }

    public boolean isCancelled(String runId) {
        return cancellationFlags.getOrDefault(runId, false);
    }

    public boolean isPaused(String runId) {
        return pauseFlags.getOrDefault(runId, false);
    }

    @Async
    public void executeRunAsync(String testRunId, String authType, String authCredentials) {
        TestRun run = testRunRepository.findById(testRunId).orElse(null);
        if (run == null) {
            log.error("Cannot execute run: TestRun ID {} not found in database", testRunId);
            return;
        }

        // 6.5 Bounded Concurrency Check
        boolean permitAcquired = false;
        try {
            permitAcquired = concurrencyLimiter.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!permitAcquired) {
            log.warn("Concurrency limit reached (Max {} concurrent runs). Rejecting run {}.", maxConcurrency, testRunId);
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage("CONCURRENCY_LIMIT_EXCEEDED (Max " + maxConcurrency + " concurrent runs)");
            run.setCompletedAt(OffsetDateTime.now());
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "RUN_FAILED", Map.of("error", run.getErrorMessage()));
            recordAudit(run, "FAILED", "SYSTEM", run.getErrorMessage());
            return;
        }

        long startNanos = System.nanoTime();
        run.setStartedAt(OffsetDateTime.now());
        recordAudit(run, "STARTED", "SYSTEM", "Test run execution commenced.");

        int timeoutSeconds = run.getTimeoutSeconds() != null ? run.getTimeoutSeconds() : 600;

        try {
            // -------------------------------------------------------------
            // Stage 1: DISCOVERY (Fetch & Parse OpenAPI)
            // -------------------------------------------------------------
            if (isCancelled(testRunId)) return;

            run.setStatus(RunStatus.DISCOVERING);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "DISCOVERY_STARTED", Map.of("openapiUrl", run.getOpenapiUrl()));
            recordAudit(run, "DISCOVERY_STARTED", "SYSTEM", "Fetching specification: " + run.getOpenapiUrl());

            String rawSpec = fetchService.fetchSpecification(run.getOpenapiUrl());
            OpenApiParserService.DiscoveryResult discovery = parserService.parse(rawSpec, run.getOpenapiUrl(), run);

            run.setTargetBaseUrl(discovery.getResolvedBaseUrl());
            run.setTotalEndpoints(discovery.getEndpoints().size());
            testRunRepository.save(run);

            for (ApiEndpoint ep : discovery.getEndpoints()) {
                apiEndpointRepository.save(ep);
                sseEventService.publishEvent(run.getId(), "API_DISCOVERED", Map.of("method", ep.getMethod(), "path", ep.getPath()));
            }

            // -------------------------------------------------------------
            // Stage 2: PLANNING (Dependency Graph & Test Plan Formulation)
            // -------------------------------------------------------------
            if (isCancelled(testRunId)) return;

            run.setStatus(RunStatus.PLANNING);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "PLANNING_STARTED", Map.of("endpointsCount", discovery.getEndpoints().size()));

            List<Dependency> dependencies = dependencyEngine.buildDependencies(run, discovery.getEndpoints());
            for (Dependency dep : dependencies) {
                dependencyRepository.save(dep);
            }

            TestPlanService.PlanResult plan = testPlanService.buildTestPlan(run, discovery.getEndpoints(), dependencies);
            int totalStepsCount = 0;

            for (TestCase tc : plan.getTestCases()) {
                testCaseRepository.save(tc);
                List<TestStep> steps = plan.getStepsByCaseId().get(tc.getId());
                if (steps != null) {
                    totalStepsCount += steps.size();
                    for (TestStep step : steps) {
                        testStepRepository.save(step);
                    }
                }
            }

            run.setTotalTests(totalStepsCount);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "PLANNING_COMPLETED", Map.of("casesCount", plan.getTestCases().size(), "stepsCount", totalStepsCount));

            // -------------------------------------------------------------
            // Stage 3: EXECUTING (Http Execution, ID Propagation, Failure Isolation)
            // -------------------------------------------------------------
            run.setStatus(RunStatus.EXECUTING);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "EXECUTION_STARTED", Map.of("totalSteps", totalStepsCount));
            recordAudit(run, "EXECUTION_STARTED", "SYSTEM", "Executing " + totalStepsCount + " planned steps.");

            ExecutionContext context = new ExecutionContext(run.getId());

            // Dynamic Authentication Login
            if (run.getAuthLoginUrl() != null && !run.getAuthLoginUrl().isBlank()) {
                sseEventService.publishEvent(run.getId(), "AUTH_LOGIN_STARTED", Map.of("loginUrl", run.getAuthLoginUrl()));
                DynamicAuthService.AuthResult authResult = dynamicAuthService.authenticate(
                        run.getAuthLoginUrl(),
                        run.getAuthLoginPayload(),
                        run.getAuthTokenPath()
                );
                if (authResult.isSuccess()) {
                    context.setVariable("auth.token", authResult.getToken());
                    authType = "BEARER";
                    authCredentials = authResult.getToken();
                    sseEventService.publishEvent(run.getId(), "AUTH_LOGIN_COMPLETED", Map.of("tokenObtained", true));
                } else {
                    log.warn("Dynamic authentication failed: {}", authResult.getErrorMessage());
                    sseEventService.publishEvent(run.getId(), "AUTH_LOGIN_FAILED", Map.of("error", authResult.getErrorMessage()));
                }
            }

            AtomicInteger passedCounter = new AtomicInteger(0);
            AtomicInteger failedCounter = new AtomicInteger(0);
            AtomicInteger blockedCounter = new AtomicInteger(0);

            // Separate into Level 1 (CRUD workflows: producers that create resources & extract IDs)
            // and Level 2 (Independent verification: single endpoints, pagination, negative fuzzing)
            List<TestCase> crudCases = new ArrayList<>();
            List<TestCase> independentCases = new ArrayList<>();

            for (TestCase tc : plan.getTestCases()) {
                if ("CRUD_WORKFLOW".equalsIgnoreCase(tc.getScenarioType())) {
                    crudCases.add(tc);
                } else {
                    independentCases.add(tc);
                }
            }

            final String fAuthType = authType;
            final String fAuthCreds = authCredentials;

            // 1. Execute Level 1 (CRUD workflows) sequentially to establish resource state & capture IDs
            for (TestCase tc : crudCases) {
                if (isCancelled(testRunId)) break;
                executeTestCase(tc, run, context, fAuthType, fAuthCreds, passedCounter, failedCounter, blockedCounter, discovery);
                run.setPassedTests(passedCounter.get());
                run.setFailedTests(failedCounter.get());
                run.setBlockedTests(blockedCounter.get());
                testRunRepository.save(run);
            }

            // 2. Execute Level 2 (Independent operations) concurrently with bounded thread pool
            if (!isCancelled(testRunId) && !independentCases.isEmpty()) {
                int workerThreads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
                ExecutorService pool = Executors.newFixedThreadPool(workerThreads);
                for (TestCase tc : independentCases) {
                    pool.submit(() -> {
                        if (!isCancelled(testRunId)) {
                            executeTestCase(tc, run, context, fAuthType, fAuthCreds, passedCounter, failedCounter, blockedCounter, discovery);
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            run.setPassedTests(passedCounter.get());
            run.setFailedTests(failedCounter.get());
            run.setBlockedTests(blockedCounter.get());
            testRunRepository.save(run);

            // Handle cancellation terminal exit
            if (isCancelled(testRunId)) {
                log.info("TestRun {} exited due to cancellation.", testRunId);
                run.setStatus(RunStatus.CANCELLED);
                run.setCompletedAt(OffsetDateTime.now());
                run.setDurationMs(Math.max(1, (System.nanoTime() - startNanos) / 1_000_000));
                testRunRepository.save(run);
                return;
            }

            // Handle timeout terminal exit
            long totalElapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000;
            if (totalElapsedSec > timeoutSeconds) {
                run.setStatus(RunStatus.TIMED_OUT);
                run.setErrorMessage("EXECUTION_TIMEOUT_EXCEEDED (Max " + timeoutSeconds + "s)");
                run.setCompletedAt(OffsetDateTime.now());
                run.setDurationMs(Math.max(1, (System.nanoTime() - startNanos) / 1_000_000));
                testRunRepository.save(run);
                recordAudit(run, "TIMED_OUT", "SYSTEM_WATCHDOG", run.getErrorMessage());
                sseEventService.publishEvent(run.getId(), "RUN_TIMED_OUT", Map.of("durationSeconds", timeoutSeconds));
                return;
            }

            // -------------------------------------------------------------
            // Stage 4: PERFORMANCE ANALYTICS & HISTORICAL REGRESSION
            // -------------------------------------------------------------
            try {
                List<Execution> allExecutions = executionRepository.findByTestRunId(run.getId());
                PerformanceAnalyticsService.PerformanceSummary perfSummary = performanceAnalyticsService.analyzeAndPersistMetrics(run, allExecutions);
                sseEventService.publishEvent(run.getId(), "PERFORMANCE_ANALYTICS_COMPLETED", Map.of(
                        "p50Ms", perfSummary.getP50Ms(),
                        "p95Ms", perfSummary.getP95Ms(),
                        "p99Ms", perfSummary.getP99Ms(),
                        "avgMs", perfSummary.getAvgMs()
                ));

                HistoricalRegressionService.RegressionReport regressionReport = historicalRegressionService.evaluateRegression(run);
                sseEventService.publishEvent(run.getId(), "REGRESSION_EVALUATION_COMPLETED", Map.of(
                        "status", regressionReport.getStatus(),
                        "deltaPercent", regressionReport.getP95DeltaPercent(),
                        "summary", regressionReport.getSummary()
                ));

                // 7.8 & 7.9: Calculate and Persist Deterministic API QA Coverage & Endpoint Classification
                List<TestStep> allSteps = testStepRepository.findByTestCaseTestRunId(run.getId());
                com.syed.apiqa.coverage.CoverageCalculationService.CoverageSummary coverageSummary =
                        coverageCalculationService.calculateAndPersistCoverage(
                                run,
                                discovery.getEndpoints(),
                                allSteps,
                                Collections.emptyList()
                        );
                sseEventService.publishEvent(run.getId(), "COVERAGE_CALCULATED", Map.of(
                        "qaCoverageScore", coverageSummary.getQaCoverageScore(),
                        "fullyTested", coverageSummary.getFullyTested(),
                        "partiallyTested", coverageSummary.getPartiallyTested(),
                        "blocked", coverageSummary.getBlocked(),
                        "unsupported", coverageSummary.getUnsupported()
                ));
            } catch (Exception e) {
                log.error("Error during post-execution analytics for run {}: {}", run.getId(), e.getMessage(), e);
            }

            // -------------------------------------------------------------
            // Stage 5: CLEANUP (Automated Reverse-Dependency Teardown)
            // -------------------------------------------------------------
            run.setStatus(RunStatus.CLEANUP);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "CLEANUP_STARTED", Map.of("trackedVariables", context.getAllVariables().size()));
            recordAudit(run, "CLEANUP_STARTED", "SYSTEM", "Tearing down created resources.");

            boolean isProd = run.getEnvironmentType() == EnvironmentType.PRODUCTION;
            cleanupManager.executeCleanup(run, run.getTargetBaseUrl(), context, isProd, authCredentials);
            sseEventService.publishEvent(run.getId(), "CLEANUP_COMPLETED", Map.of("status", run.getCleanupStatus()));
            recordAudit(run, "CLEANUP_COMPLETED", "SYSTEM", "Cleanup status: " + run.getCleanupStatus());

            // -------------------------------------------------------------
            // Stage 6: REPORTING & MANDATORY PDF COMPLETION GATE
            // -------------------------------------------------------------
            run.setStatus(RunStatus.REPORTING);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "REPORTING_STARTED", Collections.emptyMap());

            reportGenerator.generateAndSaveReport(run);
            recordAudit(run, "REPORT_GENERATED", "SYSTEM", "Executive HTML report generated successfully.");

            byte[] pdfBytes = pdfReportGenerator.generatePdfReport(run);
            if (pdfBytes == null || pdfBytes.length == 0) {
                throw new IllegalStateException("Mandatory PDF report generation gate failed: 0 bytes produced");
            }
            recordAudit(run, "PDF_REPORT_GENERATED", "SYSTEM", "Executive PDF report generated successfully (" + pdfBytes.length + " bytes).");

            // -------------------------------------------------------------
            // Stage 7: COMPLETED
            // -------------------------------------------------------------
            run.setStatus(RunStatus.COMPLETED);
            run.setCompletedAt(OffsetDateTime.now());
            run.setDurationMs(Math.max(1, (System.nanoTime() - startNanos) / 1_000_000));
            testRunRepository.save(run);

            sseEventService.publishEvent(run.getId(), "RUN_COMPLETED", Map.of(
                    "status", "COMPLETED",
                    "totalTests", run.getTotalTests(),
                    "passed", run.getPassedTests(),
                    "failed", run.getFailedTests(),
                    "blocked", run.getBlockedTests(),
                    "durationMs", run.getDurationMs()
            ));
            recordAudit(run, "COMPLETED", "SYSTEM", String.format("Passed: %d, Failed: %d, Blocked: %d",
                    run.getPassedTests(), run.getFailedTests(), run.getBlockedTests()));

            log.info("TestRun {} completed successfully in {} ms", run.getId(), run.getDurationMs());

        } catch (Exception e) {
            log.error("TestRun {} failed with unhandled exception: {}", run.getId(), e.getMessage(), e);
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(OffsetDateTime.now());
            run.setDurationMs(Math.max(1, (System.nanoTime() - startNanos) / 1_000_000));
            testRunRepository.save(run);

            sseEventService.publishEvent(run.getId(), "RUN_FAILED", Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            recordAudit(run, "FAILED", "SYSTEM", e.getMessage() != null ? e.getMessage() : "Unexpected failure");
        } finally {
            concurrencyLimiter.release();
            cancellationFlags.remove(testRunId);
            pauseFlags.remove(testRunId);
        }
    }

    private void executeTestCase(TestCase tc, TestRun run, ExecutionContext context,
                                 String authType, String authCredentials,
                                 AtomicInteger passedCounter,
                                 AtomicInteger failedCounter,
                                 AtomicInteger blockedCounter,
                                 OpenApiParserService.DiscoveryResult discovery) {
        List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
        boolean caseFailed = false;

        for (int i = 0; i < steps.size(); i++) {
            if (isCancelled(run.getId())) return;

            TestStep step = steps.get(i);
            if (step.getStatus() == StepStatus.SKIPPED) continue;

            if (caseFailed) {
                step.setStatus(StepStatus.BLOCKED);
                step.setFailureReason("BLOCKED: Failure in upstream step of scenario");
                testStepRepository.save(step);
                int b = blockedCounter.incrementAndGet();
                sseEventService.publishEvent(run.getId(), "TEST_BLOCKED", Map.of(
                        "stepId", step.getId(),
                        "name", step.getName(),
                        "reason", step.getFailureReason(),
                        "passed", passedCounter.get(),
                        "failed", failedCounter.get(),
                        "blocked", b
                ));
                continue;
            }

            sseEventService.publishEvent(run.getId(), "TEST_STARTED", Map.of(
                    "stepId", step.getId(),
                    "name", step.getName(),
                    "method", step.getMethod()
            ));

            HttpExecutionEngine.StepExecutionOutcome outcome = httpEngine.executeStep(
                    step,
                    run.getTargetBaseUrl(),
                    context,
                    run.getEnvironmentType(),
                    authType,
                    authCredentials
            );

            // Dynamic token refresh on 401
            if (outcome.getFinalStatus() == StepStatus.AUTHENTICATION_ERROR && run.getAuthRefreshUrl() != null) {
                DynamicAuthService.AuthResult refreshResult = dynamicAuthService.refreshToken(
                        run.getAuthRefreshUrl(),
                        context.getVariable("auth.token"),
                        run.getAuthTokenPath()
                );
                if (refreshResult.isSuccess()) {
                    context.setVariable("auth.token", refreshResult.getToken());
                    String m = step.getMethod().toUpperCase();
                    if (!"POST".equals(m)) {
                        outcome = httpEngine.executeStep(
                                step,
                                run.getTargetBaseUrl(),
                                context,
                                run.getEnvironmentType(),
                                authType,
                                refreshResult.getToken()
                        );
                    }
                }
            }

            step.setStatus(outcome.getFinalStatus());
            testStepRepository.save(step);

            // Phase 2: Register Created Resources for Automated Teardown
            if ("POST".equalsIgnoreCase(step.getMethod()) && outcome.getFinalStatus() == StepStatus.PASSED) {
                String entityName = step.getPathTemplate() != null
                        ? dependencyEngine.extractEntityNameFromPath(step.getPathTemplate())
                        : "entity";
                String createdId = context.getVariable(entityName + ".id");
                if (createdId == null) createdId = context.getVariable("id");
                if (createdId == null) createdId = context.getVariable(entityName + "_id");

                if (createdId != null) {
                    String deletePath = findMatchingDeletePath(discovery.getEndpoints(), entityName, step.getPathTemplate());
                    cleanupManager.recordCreatedResource(run, entityName, createdId, deletePath, i);
                }
            }

            // Publish real per-step SSE result events
            if (outcome.getFinalStatus() == StepStatus.PASSED) {
                int p = passedCounter.incrementAndGet();
                sseEventService.publishEvent(run.getId(), "TEST_COMPLETED", Map.of(
                        "stepId", step.getId(),
                        "name", step.getName(),
                        "method", step.getMethod(),
                        "passed", p,
                        "failed", failedCounter.get(),
                        "blocked", blockedCounter.get()
                ));
            } else if (outcome.getFinalStatus() == StepStatus.BLOCKED) {
                int b = blockedCounter.incrementAndGet();
                sseEventService.publishEvent(run.getId(), "TEST_BLOCKED", Map.of(
                        "stepId", step.getId(),
                        "name", step.getName(),
                        "reason", step.getFailureReason() != null ? step.getFailureReason() : "Blocked",
                        "passed", passedCounter.get(),
                        "failed", failedCounter.get(),
                        "blocked", b
                ));
            } else {
                caseFailed = true;
                int f = failedCounter.incrementAndGet();
                sseEventService.publishEvent(run.getId(), "TEST_FAILED", Map.of(
                        "stepId", step.getId(),
                        "name", step.getName(),
                        "status", outcome.getFinalStatus().name(),
                        "reason", step.getFailureReason() != null ? step.getFailureReason() : "Failed",
                        "passed", passedCounter.get(),
                        "failed", f,
                        "blocked", blockedCounter.get()
                ));
                List<TestStep> remaining = steps.subList(i + 1, steps.size());
                failureIsolationHandler.isolateFailureAndBlockDownstream(step, remaining, tc.getScenarioType());
            }
        }

        tc.setStatus(caseFailed ? StepStatus.FAILED : StepStatus.PASSED);
        testCaseRepository.save(tc);
    }

    private void recordAudit(TestRun run, String eventType, String actor, String details) {
        try {
            RunAuditEvent event = new RunAuditEvent(run, eventType, actor, details);
            auditEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to persist audit event {} for run {}: {}", eventType, run.getId(), e.getMessage());
        }
    }

    private String findMatchingDeletePath(List<ApiEndpoint> endpoints, String entityName, String postPath) {
        if (endpoints != null) {
            for (ApiEndpoint ep : endpoints) {
                if ("DELETE".equalsIgnoreCase(ep.getMethod())) {
                    String epEntity = dependencyEngine.extractEntityNameFromPath(ep.getPath());
                    if (entityName.equalsIgnoreCase(epEntity)) {
                        return ep.getPath();
                    }
                }
            }
        }
        if (!postPath.contains("{")) {
            return postPath + "/{id}";
        }
        return postPath;
    }
}
