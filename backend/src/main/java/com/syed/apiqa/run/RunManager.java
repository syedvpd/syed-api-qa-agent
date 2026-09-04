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
import io.swagger.v3.oas.models.media.Schema;
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
    private final com.syed.apiqa.auth.engine.AuthenticationPreflightService preflightService;
    private final com.syed.apiqa.auth.engine.IdentitySessionManager identitySessionManager;
    private final com.syed.apiqa.auth.matrix.AuthorizationMatrixEngine authorizationMatrixEngine;
    private final com.syed.apiqa.discovery.ContractNormalizationService normalizationService;
    private final com.syed.apiqa.intelligence.FailureIntelligenceService failureIntelligenceService;
    private final com.syed.apiqa.auth.engine.SecurityDecisionEngine securityDecisionEngine;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.syed.apiqa.discovery.OpenApiSchemaRegistry openApiSchemaRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.syed.apiqa.persistence.SpecificationSnapshotRepository specSnapshotRepository;

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
                      com.syed.apiqa.safety.SecretMasker secretMasker,
                      com.syed.apiqa.auth.engine.AuthenticationPreflightService preflightService,
                      com.syed.apiqa.auth.engine.IdentitySessionManager identitySessionManager,
                      com.syed.apiqa.auth.matrix.AuthorizationMatrixEngine authorizationMatrixEngine,
                      com.syed.apiqa.discovery.ContractNormalizationService normalizationService,
                      com.syed.apiqa.intelligence.FailureIntelligenceService failureIntelligenceService,
                      com.syed.apiqa.auth.engine.SecurityDecisionEngine securityDecisionEngine) {
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
        this.preflightService = preflightService;
        this.identitySessionManager = identitySessionManager;
        this.authorizationMatrixEngine = authorizationMatrixEngine;
        this.normalizationService = normalizationService;
        this.failureIntelligenceService = failureIntelligenceService;
        this.securityDecisionEngine = securityDecisionEngine;
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
        executeRunAsync(testRunId, authType, authCredentials, null);
    }

    @Async
    public void executeRunAsync(String testRunId, String authType, String authCredentials, List<com.syed.apiqa.auth.CredentialProfile> profiles) {
        TestRun run = testRunRepository.findById(testRunId).orElse(null);
        if (run == null) {
            log.error("Cannot execute run: TestRun ID {} not found in database", testRunId);
            return;
        }

        // 6.5 Bounded Concurrency & Queueing with Backpressure
        boolean permitAcquired = concurrencyLimiter.tryAcquire();
        if (!permitAcquired) {
            log.info("Max active concurrency ({}) reached. Transitioning TestRun {} to QUEUED state.", maxConcurrency, testRunId);
            run.setStatus(RunStatus.QUEUED);
            testRunRepository.save(run);
            sseEventService.publishEvent(run.getId(), "RUN_QUEUED", Map.of(
                    "status", "QUEUED",
                    "message", "Run queued. Waiting for an execution worker slot."
            ));
            recordAudit(run, "QUEUED", "SYSTEM", "Concurrency limit reached. Placed in execution queue with backpressure.");

            int queueTimeoutSeconds = 300; // 5 minutes queue timeout
            try {
                permitAcquired = concurrencyLimiter.tryAcquire(queueTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!permitAcquired) {
                log.warn("Queue wait timeout exceeded ({}s) for run {}.", queueTimeoutSeconds, testRunId);
                run.setStatus(RunStatus.TIMED_OUT);
                run.setErrorMessage("QUEUE_TIMEOUT: Exceeded queue wait limit of " + queueTimeoutSeconds + "s.");
                run.setCompletedAt(OffsetDateTime.now());
                testRunRepository.save(run);
                sseEventService.publishEvent(run.getId(), "RUN_TIMED_OUT", Map.of("error", run.getErrorMessage()));
                recordAudit(run, "TIMED_OUT", "SYSTEM", run.getErrorMessage());
                return;
            }
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

            com.syed.apiqa.discovery.OpenApiDiscoveryResult discoveryResult = fetchService.fetchSpecificationResult(run.getOpenapiUrl());
            String rawSpec = discoveryResult.getContent();
            String specUrlToParse = discoveryResult.getDiscoveredSpecUrl();

            if (!discoveryResult.getOriginalUrl().equalsIgnoreCase(specUrlToParse)) {
                log.info("Specification resolved via {}: Input={} -> Spec={}",
                        discoveryResult.getDiscoveryMethod(), discoveryResult.getOriginalUrl(), specUrlToParse);
                recordAudit(run, "SPEC_DISCOVERED", "SYSTEM",
                        "Specification resolved via " + discoveryResult.getDiscoveryMethod() + ": " + specUrlToParse);
                sseEventService.publishEvent(run.getId(), "SPEC_DISCOVERED", Map.of(
                        "inputUrl", discoveryResult.getOriginalUrl(),
                        "discoveredSpecUrl", specUrlToParse,
                        "discoveryMethod", discoveryResult.getDiscoveryMethod().name()
                ));
            }

            OpenApiParserService.DiscoveryResult discovery = parserService.parse(rawSpec, specUrlToParse, run);

            run.setTargetBaseUrl(discovery.getResolvedBaseUrl());
            run.setTotalEndpoints(discovery.getEndpoints().size());
            testRunRepository.save(run);

            if (specSnapshotRepository != null) {
                try {
                    String oasVer = (discovery.getOpenAPI() != null && discovery.getOpenAPI().getOpenapi() != null)
                            ? discovery.getOpenAPI().getOpenapi() : "3.0.0";
                    com.syed.apiqa.domain.SpecificationSnapshot snapshot = new com.syed.apiqa.domain.SpecificationSnapshot(
                            UUID.randomUUID().toString(),
                            run,
                            discoveryResult.getOriginalUrl(),
                            specUrlToParse,
                            oasVer,
                            discovery.getResolvedBaseUrl(),
                            discovery.getEndpoints().size(),
                            rawSpec
                    );
                    specSnapshotRepository.save(snapshot);
                    recordAudit(run, "SPEC_SNAPSHOT_SAVED", "SYSTEM", "Immutable specification snapshot persisted (" + rawSpec.length() + " bytes).");
                } catch (Exception e) {
                    log.warn("Failed to persist SpecificationSnapshot for run {}: {}", run.getId(), e.getMessage());
                }
            }

            for (ApiEndpoint ep : discovery.getEndpoints()) {
                apiEndpointRepository.save(ep);
                sseEventService.publishEvent(run.getId(), "API_DISCOVERED", Map.of("method", ep.getMethod(), "path", ep.getPath()));
            }

            if (openApiSchemaRegistry != null && discovery.getOpenAPI() != null) {
                openApiSchemaRegistry.registerOpenApi(run.getId(), discovery.getOpenAPI());
            }

            // Multi-Identity Authentication Preflight & Matrix Initialization
            List<com.syed.apiqa.auth.CredentialProfile> activeProfiles = profiles;
            if ((activeProfiles == null || activeProfiles.isEmpty()) && run.getCredentialProfilesJson() != null) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    activeProfiles = mapper.readValue(run.getCredentialProfilesJson(),
                            mapper.getTypeFactory().constructCollectionType(List.class, com.syed.apiqa.auth.CredentialProfile.class));
                } catch (Exception ignored) {}
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

            Map<String, Schema> schemas = (discovery.getOpenAPI() != null && discovery.getOpenAPI().getComponents() != null)
                    ? discovery.getOpenAPI().getComponents().getSchemas()
                    : null;
            TestPlanService.PlanResult plan = testPlanService.buildTestPlan(run, discovery.getEndpoints(), dependencies, schemas);
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

            var normResult = normalizationService.normalize(discovery.getOpenAPI(), run.getOpenapiUrl());
            com.syed.apiqa.domain.canonical.CanonicalApiModel canonicalModel = normResult.model();

            if (activeProfiles != null && !activeProfiles.isEmpty()) {
                sseEventService.publishEvent(run.getId(), "AUTH_PREFLIGHT_STARTED", Map.of("profilesCount", activeProfiles.size()));
                var preflightReport = preflightService.executePreflight(run.getId(), activeProfiles, discovery.getResolvedBaseUrl());
                sseEventService.publishEvent(run.getId(), "AUTH_PREFLIGHT_COMPLETED", Map.of(
                        "totalIdentities", preflightReport.totalIdentities(),
                        "authenticatedCount", preflightReport.authenticatedCount(),
                        "allPassed", preflightReport.allPassed()
                ));

                for (com.syed.apiqa.auth.CredentialProfile cp : activeProfiles) {
                    com.syed.apiqa.auth.IdentitySession session = identitySessionManager.getOrCreateSession(run.getId(), cp);
                    context.registerSession(session);
                }

                var matrixCells = authorizationMatrixEngine.buildMatrix(canonicalModel, activeProfiles, context.getAllSessions());
                sseEventService.publishEvent(run.getId(), "AUTHORIZATION_MATRIX_BUILT", Map.of(
                        "totalCombinations", matrixCells.size()
                ));
            }

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

            if (authCredentials != null && !authCredentials.isBlank()) {
                com.syed.apiqa.auth.IdentitySession primarySession = new com.syed.apiqa.auth.IdentitySession("primary-identity", "Primary Identity");
                primarySession.setState(com.syed.apiqa.auth.canonical.AuthLifecycleState.AUTHENTICATED);
                primarySession.setAccessToken(authCredentials.trim());
                primarySession.setAuthStrategy(authType != null ? authType : "BEARER");
                if ("API_KEY".equalsIgnoreCase(authType)) {
                    primarySession.setAuthHeader("X-Api-Key", authCredentials.trim());
                } else {
                    primarySession.setAuthHeader("Authorization", "Bearer " + authCredentials.trim());
                }
                context.registerSession(primarySession);
            }

            AtomicInteger passedCounter = new AtomicInteger(0);
            AtomicInteger failedCounter = new AtomicInteger(0);
            AtomicInteger blockedCounter = new AtomicInteger(0);

            final String fAuthType = authType;
            final String fAuthCreds = authCredentials;
            final List<com.syed.apiqa.auth.CredentialProfile> fActiveProfiles = activeProfiles;

            // Execute test cases via topological DAG scheduler
            int workerThreads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
            com.syed.apiqa.planning.dag.DependencyGraph graph = dependencyEngine.buildDependencyGraph(run, plan.getTestCases(), dependencies);
            com.syed.apiqa.planning.dag.DagExecutionScheduler scheduler = new com.syed.apiqa.planning.dag.DagExecutionScheduler(graph, workerThreads);

            scheduler.execute(context, (node, ctx) -> {
                if (isCancelled(testRunId)) {
                    return com.syed.apiqa.planning.dag.DagNode.NodeStatus.SKIPPED;
                }
                TestCase tc = (TestCase) node.getPayload();
                executeTestCase(tc, run, context, fAuthType, fAuthCreds, passedCounter, failedCounter, blockedCounter, discovery, fActiveProfiles);

                run.setPassedTests(passedCounter.get());
                run.setFailedTests(failedCounter.get());
                run.setBlockedTests(blockedCounter.get());
                testRunRepository.save(run);

                if (tc.getStatus() == StepStatus.PASSED) {
                    return com.syed.apiqa.planning.dag.DagNode.NodeStatus.PASSED;
                } else if (tc.getStatus() == StepStatus.REQUEST_NOT_EXECUTABLE || tc.getStatus() == StepStatus.BLOCKED) {
                    return com.syed.apiqa.planning.dag.DagNode.NodeStatus.BLOCKED;
                } else {
                    return com.syed.apiqa.planning.dag.DagNode.NodeStatus.FAILED;
                }
            });

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
                                 OpenApiParserService.DiscoveryResult discovery,
                                 List<com.syed.apiqa.auth.CredentialProfile> activeProfiles) {
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

            com.syed.apiqa.auth.engine.OperationSecurityDecision decision = securityDecisionEngine.evaluateSecurity(
                    step.getApiEndpoint(), discovery.getOpenAPI(), activeProfiles);

            com.syed.apiqa.auth.IdentitySession idSession = null;
            if (decision.getSelectedIdentity() != null && context.getAllSessions() != null) {
                idSession = context.getSession(decision.getSelectedIdentity().getId());
                if (idSession == null) {
                    idSession = context.getSession(decision.getSelectedIdentity().getName());
                }
            }
            if (idSession == null && context.getAllSessions() != null) {
                idSession = context.getSession("primary-identity");
            }

            if (decision.getSecurityState() == com.syed.apiqa.auth.engine.OperationSecurityDecision.SecurityState.AUTH_REQUIRED) {
                if (decision.getSelectedIdentity() == null || idSession == null || idSession.getState() == com.syed.apiqa.auth.canonical.AuthLifecycleState.AUTH_FAILED) {
                    step.setStatus(StepStatus.BLOCKED);
                    String reason;
                    if (decision.getSelectedIdentity() == null) {
                        reason = "BLOCKED_BY_AUTHENTICATION: " + decision.getReason();
                    } else if (idSession == null) {
                        reason = "BLOCKED_BY_AUTHENTICATION: Selected identity [" + decision.getSelectedIdentity().getName() + "] has no active session registered.";
                    } else {
                        reason = "BLOCKED_BY_AUTHENTICATION: Identity [" + idSession.getIdentityName() + "] failed authentication: "
                                + (idSession.getLastErrorMessage() != null ? idSession.getLastErrorMessage() : "credentials rejected");
                    }
                    step.setFailureReason(reason);
                    testStepRepository.save(step);
                    caseFailed = true;
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
            } else if (decision.getSecurityState() == com.syed.apiqa.auth.engine.OperationSecurityDecision.SecurityState.NO_SECURITY 
                       || decision.getSecurityState() == com.syed.apiqa.auth.engine.OperationSecurityDecision.SecurityState.AUTH_BOOTSTRAP) {
                // Do not pass the failed identity session for public or bootstrap endpoints
                idSession = null;
            }

            HttpExecutionEngine.StepExecutionOutcome outcome = httpEngine.executeStep(
                    step,
                    run.getTargetBaseUrl(),
                    context,
                    run.getEnvironmentType(),
                    authType,
                    authCredentials,
                    idSession
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
            } else if (outcome.getFinalStatus() == StepStatus.BLOCKED || outcome.getFinalStatus() == StepStatus.REQUEST_NOT_EXECUTABLE) {
                int b = blockedCounter.incrementAndGet();
                com.syed.apiqa.intelligence.DiagnosticFinding finding = failureIntelligenceService.diagnoseStep(step, outcome.getExecution());
                String formattedReason = String.format("[%s | %s | Confidence: %s] %s",
                        finding.getCategory(),
                        finding.getAttribution(),
                        finding.getConfidence(),
                        finding.getProbableRootCause());
                step.setFailureReason(formattedReason);

                Map<String, Object> payload = new HashMap<>();
                payload.put("stepId", step.getId());
                payload.put("name", step.getName());
                payload.put("status", outcome.getFinalStatus().name());
                payload.put("category", finding.getCategory().name());
                payload.put("attribution", finding.getAttribution().name());
                payload.put("confidence", finding.getConfidence().name());
                payload.put("reason", formattedReason);
                payload.put("blastRadius", finding.getBlastRadius());
                payload.put("passed", passedCounter.get());
                payload.put("failed", failedCounter.get());
                payload.put("blocked", b);
                sseEventService.publishEvent(run.getId(), "TEST_BLOCKED", payload);
            } else {
                caseFailed = true;
                int f = failedCounter.incrementAndGet();
                com.syed.apiqa.intelligence.DiagnosticFinding finding = failureIntelligenceService.diagnoseStep(step, outcome.getExecution());
                String formattedReason = String.format("[%s | %s | Confidence: %s] %s",
                        finding.getCategory(),
                        finding.getAttribution(),
                        finding.getConfidence(),
                        finding.getProbableRootCause());
                step.setFailureReason(formattedReason);

                Map<String, Object> payload = new HashMap<>();
                payload.put("stepId", step.getId());
                payload.put("name", step.getName());
                payload.put("status", outcome.getFinalStatus().name());
                payload.put("category", finding.getCategory().name());
                payload.put("attribution", finding.getAttribution().name());
                payload.put("confidence", finding.getConfidence().name());
                payload.put("reason", formattedReason);
                payload.put("blastRadius", finding.getBlastRadius());
                payload.put("passed", passedCounter.get());
                payload.put("failed", f);
                payload.put("blocked", blockedCounter.get());
                sseEventService.publishEvent(run.getId(), "TEST_FAILED", payload);
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
