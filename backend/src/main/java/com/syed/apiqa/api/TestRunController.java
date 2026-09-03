package com.syed.apiqa.api;

import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.reporting.PdfReportGenerator;
import com.syed.apiqa.run.RunManager;
import com.syed.apiqa.run.SseEventService;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/api/runs")
public class TestRunController {

    private final TestRunRepository testRunRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestStepRepository testStepRepository;
    private final ExecutionRepository executionRepository;
    private final CleanupRecordRepository cleanupRecordRepository;
    private final PerformanceMetricRepository performanceMetricRepository;
    private final ReportRepository reportRepository;
    private final PdfReportGenerator pdfReportGenerator;
    private final com.syed.apiqa.reporting.HtmlReportGenerator htmlReportGenerator;
    private final RunManager runManager;
    private final SseEventService sseEventService;
    private final SsrfProtectionGuard ssrfGuard;
    private final com.syed.apiqa.regression.HistoricalRegressionService regressionService;
    private final com.syed.apiqa.persistence.RegressionFindingRepository regressionFindingRepository;
    private final com.syed.apiqa.persistence.RunAuditEventRepository auditEventRepository;
    private final com.syed.apiqa.persistence.EndpointCoverageRepository endpointCoverageRepository;
    private final com.syed.apiqa.auth.engine.AuthenticationPreflightService preflightService;

    public TestRunController(TestRunRepository testRunRepository,
                             ApiEndpointRepository apiEndpointRepository,
                             TestCaseRepository testCaseRepository,
                             TestStepRepository testStepRepository,
                             ExecutionRepository executionRepository,
                             CleanupRecordRepository cleanupRecordRepository,
                             PerformanceMetricRepository performanceMetricRepository,
                             ReportRepository reportRepository,
                             PdfReportGenerator pdfReportGenerator,
                             com.syed.apiqa.reporting.HtmlReportGenerator htmlReportGenerator,
                             RunManager runManager,
                             SseEventService sseEventService,
                             SsrfProtectionGuard ssrfGuard,
                             com.syed.apiqa.regression.HistoricalRegressionService regressionService,
                             com.syed.apiqa.persistence.RegressionFindingRepository regressionFindingRepository,
                             com.syed.apiqa.persistence.RunAuditEventRepository auditEventRepository,
                             com.syed.apiqa.persistence.EndpointCoverageRepository endpointCoverageRepository,
                             com.syed.apiqa.auth.engine.AuthenticationPreflightService preflightService) {
        this.testRunRepository = testRunRepository;
        this.apiEndpointRepository = apiEndpointRepository;
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
        this.cleanupRecordRepository = cleanupRecordRepository;
        this.performanceMetricRepository = performanceMetricRepository;
        this.reportRepository = reportRepository;
        this.pdfReportGenerator = pdfReportGenerator;
        this.htmlReportGenerator = htmlReportGenerator;
        this.runManager = runManager;
        this.sseEventService = sseEventService;
        this.ssrfGuard = ssrfGuard;
        this.regressionService = regressionService;
        this.regressionFindingRepository = regressionFindingRepository;
        this.auditEventRepository = auditEventRepository;
        this.endpointCoverageRepository = endpointCoverageRepository;
        this.preflightService = preflightService;
    }

    @GetMapping
    public ResponseEntity<List<TestRun>> listRuns(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        String requesterId = resolveRequesterId(userId, principal);
        List<TestRun> runs = testRunRepository.findByOrderByCreatedAtDesc();
        if (requesterId != null && !requesterId.isBlank()) {
            runs = runs.stream()
                    .filter(r -> r.getOwnerId() == null || r.getOwnerId().isBlank() || r.getOwnerId().equals(requesterId))
                    .collect(java.util.stream.Collectors.toList());
        }
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRun(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();
        ResponseEntity<?> authCheck = checkOwnership(run, userId, principal);
        if (authCheck != null) return authCheck;
        return ResponseEntity.ok(run);
    }

    @PostMapping
    public ResponseEntity<?> createAndLaunchRun(
            @RequestBody com.syed.apiqa.api.dto.CreateRunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {

        // 6.4 Idempotency Check: return existing run if key was already submitted
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<TestRun> existing = testRunRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                return ResponseEntity.ok(existing.get());
            }
        }

        String openapiUrl = request.getOpenapiUrl();
        String envTypeStr = request.getEnvironmentType();
        String authType = request.getAuthType() != null ? request.getAuthType() : "NONE";
        String authCredentials = request.getAuthCredentials();
        if (authCredentials == null || authCredentials.isBlank()) {
            authCredentials = request.getAuthToken();
        }

        if (openapiUrl == null || openapiUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "openapiUrl is required"));
        }

        EnvironmentType environmentType = EnvironmentType.STAGING;
        try {
            if ("LOCAL".equalsIgnoreCase(envTypeStr)) {
                environmentType = EnvironmentType.DEVELOPMENT;
            } else if (envTypeStr != null) {
                environmentType = EnvironmentType.valueOf(envTypeStr.toUpperCase());
            }
        } catch (IllegalArgumentException ignored) {}

        // Validate target URL against SSRF and private IP blocklist (allow local if DEVELOPMENT mode or configured)
        boolean allowLocal = (environmentType == EnvironmentType.DEVELOPMENT) || ssrfGuard.isAllowLocalTargets();
        try {
            ssrfGuard.validateTargetUrl(openapiUrl, allowLocal);
        } catch (SecurityException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        String requesterId = resolveRequesterId(userId, principal);
        TestRun run = new TestRun(UUID.randomUUID().toString(), openapiUrl, environmentType);
        run.setOwnerId(requesterId);
        run.setIdempotencyKey(idempotencyKey);
        run.setAuthLoginUrl(request.getAuthLoginUrl());
        run.setAuthLoginPayload(request.getAuthLoginPayload());
        run.setAuthTokenPath(request.getAuthTokenPath());
        run.setAuthRefreshUrl(request.getAuthRefreshUrl());
        if (request.getTimeoutSeconds() != null) {
            run.setTimeoutSeconds(request.getTimeoutSeconds());
        }

        if (request.getProfiles() != null && !request.getProfiles().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                run.setCredentialProfilesJson(mapper.writeValueAsString(request.getProfiles()));
            } catch (Exception ignored) {}
        }

        testRunRepository.save(run);

        // Trigger autonomous background execution with typed profiles
        runManager.executeRunAsync(run.getId(), authType, authCredentials, request.getProfiles());

        return ResponseEntity.status(201).body(Map.of(
                "runId", run.getId(),
                "status", run.getStatus().name(),
                "environmentType", run.getEnvironmentType().name(),
                "message", "Autonomous test run initiated in background"
        ));
    }

    @PostMapping("/preflight")
    public ResponseEntity<?> testPreflight(@RequestBody Map<String, Object> request) {
        String openapiUrl = (String) request.get("openapiUrl");
        String baseUrl = openapiUrl;
        if (baseUrl != null && baseUrl.contains("/v3/") || (baseUrl != null && baseUrl.contains("/swagger"))) {
            int idx = baseUrl.indexOf("/", 8);
            if (idx > 0) baseUrl = baseUrl.substring(0, idx);
        }

        List<com.syed.apiqa.auth.CredentialProfile> profiles = new ArrayList<>();
        if (request.containsKey("profiles") && request.get("profiles") instanceof List<?> list) {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Object obj : list) {
                try {
                    com.syed.apiqa.auth.CredentialProfile cp = mapper.convertValue(obj, com.syed.apiqa.auth.CredentialProfile.class);
                    if (cp != null) profiles.add(cp);
                } catch (Exception ignored) {}
            }
        }

        var report = preflightService.executePreflight("preflight_" + UUID.randomUUID(), profiles, baseUrl);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelRun(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }

        String reason = body != null && body.containsKey("reason") ? body.get("reason") : "User requested cancellation";
        boolean success = runManager.cancelRun(id, requesterId != null ? requesterId : "USER", reason);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of("error", "Run is already terminal or cannot be cancelled in state: " + run.getStatus()));
        }
        return ResponseEntity.ok(Map.of("message", "Run cancellation requested successfully", "status", "CANCELLED"));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pauseRun(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }

        boolean paused = runManager.pauseRun(id, requesterId != null ? requesterId : "USER");
        if (!paused) {
            return ResponseEntity.badRequest().body(Map.of("error", "Run cannot be paused in status: " + run.getStatus()));
        }
        return ResponseEntity.ok(Map.of("message", "Run paused successfully", "status", "PAUSED"));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resumeRun(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }

        boolean resumed = runManager.resumeRun(id, requesterId != null ? requesterId : "USER");
        if (!resumed) {
            return ResponseEntity.badRequest().body(Map.of("error", "Run cannot be resumed from status: " + run.getStatus()));
        }
        return ResponseEntity.ok(Map.of("message", "Run resumed successfully", "status", "EXECUTING"));
    }

    @GetMapping("/{id}/audit")
    public ResponseEntity<?> getAuditEvents(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(auditEventRepository.findByTestRunIdOrderByCreatedAtAsc(id));
    }

    private String resolveRequesterId(String userId, java.security.Principal principal) {
        String verified = com.syed.apiqa.security.SecurityContext.getCurrentUserId();
        if (verified != null && !verified.isBlank()) return verified;
        if (principal != null) return principal.getName();
        if (userId != null && !userId.isBlank()) return userId.trim();
        return null;
    }

    /**
     * Centralized ownership check. Returns a non-null ResponseEntity if access is denied.
     * Returns null if access is permitted.
     */
    private ResponseEntity<?> checkOwnership(TestRun run, String userId, java.security.Principal principal) {
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            String requesterId = resolveRequesterId(userId, principal);
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }
        return null;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isPresent()) {
            TestRun run = runOpt.get();
            if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
                String requesterId = resolveRequesterId(userId, principal);
                if (requesterId == null || !run.getOwnerId().equals(requesterId)) {
                    SseEmitter rejected = new SseEmitter(0L);
                    rejected.completeWithError(new SecurityException("Access denied"));
                    return rejected;
                }
            }
        }
        return sseEventService.subscribe(id);
    }

    @GetMapping("/{id}/endpoints")
    public ResponseEntity<?> getEndpoints(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        ResponseEntity<?> authCheck = checkOwnership(runOpt.get(), userId, principal);
        if (authCheck != null) return authCheck;
        return ResponseEntity.ok(apiEndpointRepository.findByTestRunId(id));
    }

    @GetMapping("/{id}/cases")
    public ResponseEntity<?> getCasesWithSteps(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        ResponseEntity<?> authCheck = checkOwnership(runOpt.get(), userId, principal);
        if (authCheck != null) return authCheck;

        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(id);
        List<Map<String, Object>> result = new ArrayList<>();

        for (TestCase tc : cases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            Map<String, Object> caseData = new HashMap<>();
            caseData.put("case", tc);
            caseData.put("steps", steps);
            result.add(caseData);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/coverage")
    public ResponseEntity<?> getCoverage(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {

        TestRun run = testRunRepository.findById(id).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Access denied: Unauthorized to inspect coverage for this test run"));
            }
        }

        List<com.syed.apiqa.domain.EndpointCoverage> coverages = endpointCoverageRepository.findByTestRunIdOrderByPathAsc(id);
        Map<String, Object> response = new HashMap<>();
        response.put("score", run.getCoverageScore());
        response.put("summaryJson", run.getCoverageSummaryJson());
        response.put("endpoints", coverages);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<?> getReport(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        ResponseEntity<?> authCheck = checkOwnership(runOpt.get(), userId, principal);
        if (authCheck != null) return authCheck;
        return reportRepository.findByTestRunId(id)
                .or(() -> {
                    try {
                        return Optional.ofNullable(htmlReportGenerator.generateAndSaveReport(runOpt.get()));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                })
                .map(r -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(r.getHtmlContent()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/report/summary")
    public ResponseEntity<?> getReportSummary(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        ResponseEntity<?> authCheck = checkOwnership(runOpt.get(), userId, principal);
        if (authCheck != null) return authCheck;
        return reportRepository.findByTestRunId(id)
                .map(r -> ResponseEntity.ok(Map.of(
                        "reportId", r.getId(),
                        "generatedAt", r.getGeneratedAt(),
                        "summary", r.getSummaryJson() != null ? r.getSummaryJson() : "{}"
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/cleanup")
    public ResponseEntity<?> getCleanupRecords(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        ResponseEntity<?> authCheck = checkOwnership(runOpt.get(), userId, principal);
        if (authCheck != null) return authCheck;
        return ResponseEntity.ok(cleanupRecordRepository.findByTestRunIdOrderByExecutionOrderDesc(id));
    }

    @GetMapping("/{id}/performance")
    public ResponseEntity<?> getPerformanceMetrics(@PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        ResponseEntity<?> authCheck = checkOwnership(runOpt.get(), userId, principal);
        if (authCheck != null) return authCheck;
        return ResponseEntity.ok(performanceMetricRepository.findByTestRunId(id));
    }

    @GetMapping("/{id}/regression")
    public ResponseEntity<?> getRegressionSummary(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        return testRunRepository.findById(id)
                .map(r -> {
                    String requesterId = (userId != null && !userId.isBlank())
                            ? userId.trim()
                            : (principal != null ? principal.getName() : null);
                    if (r.getOwnerId() != null && !r.getOwnerId().isBlank()) {
                        if (requesterId == null) return ResponseEntity.status(401).build();
                        if (!r.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
                    }
                    List<com.syed.apiqa.domain.RegressionFinding> findings = regressionFindingRepository.findByTestRunIdOrderByCreatedAtDesc(id);
                    return ResponseEntity.ok(Map.of(
                            "runId", r.getId(),
                            "baselineRunId", r.getBaselineRunId() != null ? r.getBaselineRunId() : "",
                            "regressionSummary", r.getRegressionSummaryJson() != null ? r.getRegressionSummaryJson() : "{}",
                            "findings", findings
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/regression/compare")
    public ResponseEntity<?> compareWithBaseline(
            @PathVariable String id,
            @RequestParam String baselineId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }

        Optional<TestRun> baselineOpt = testRunRepository.findById(baselineId);
        if (baselineOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Baseline run not found: " + baselineId));
        }

        // Verify baseline run ownership as well
        TestRun baselineRun = baselineOpt.get();
        if (baselineRun.getOwnerId() != null && !baselineRun.getOwnerId().isBlank()) {
            if (requesterId == null || !baselineRun.getOwnerId().equals(requesterId)) {
                return ResponseEntity.status(403).body(Map.of("error", "Cannot compare with baseline owned by another user."));
            }
        }

        com.syed.apiqa.regression.HistoricalRegressionService.RegressionReport report = regressionService.evaluateRegression(run, baselineId);
        List<com.syed.apiqa.domain.RegressionFinding> findings = regressionFindingRepository.findByTestRunIdOrderByCreatedAtDesc(id);
        return ResponseEntity.ok(Map.of(
                "report", report,
                "findings", findings
        ));
    }

    @GetMapping("/{id}/baselines")
    public ResponseEntity<?> getAvailableBaselines(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {
        Optional<TestRun> runOpt = testRunRepository.findById(id);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        TestRun run = runOpt.get();

        String requesterId = resolveRequesterId(userId, principal);
        if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
            if (requesterId == null) return ResponseEntity.status(401).build();
            if (!run.getOwnerId().equals(requesterId)) return ResponseEntity.status(403).build();
        }

        List<TestRun> candidates = testRunRepository.findByOrderByCreatedAtDesc().stream()
                .filter(r -> !r.getId().equals(run.getId()))
                .filter(r -> r.getStatus() == RunStatus.COMPLETED)
                .filter(r -> run.getOpenapiUrl() != null && run.getOpenapiUrl().equalsIgnoreCase(r.getOpenapiUrl()))
                .filter(r -> {
                    if (run.getOwnerId() == null || run.getOwnerId().isBlank()) return true;
                    return run.getOwnerId().equals(r.getOwnerId());
                })
                .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(candidates);
    }

    @GetMapping("/{id}/report/pdf")
    public ResponseEntity<byte[]> getPdfReport(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            java.security.Principal principal) {

        return testRunRepository.findById(id)
                .map(run -> {
                    String requesterId = resolveRequesterId(userId, principal);

                    if (run.getOwnerId() != null && !run.getOwnerId().isBlank()) {
                        if (requesterId == null) {
                            return ResponseEntity.status(401).<byte[]>build();
                        }
                        if (!run.getOwnerId().equals(requesterId)) {
                            return ResponseEntity.status(403).<byte[]>build();
                        }
                    }

                    byte[] pdfBytes = pdfReportGenerator.generatePdfReport(run);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"syed-qa-report-" + id + ".pdf\"")
                            .body(pdfBytes);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
