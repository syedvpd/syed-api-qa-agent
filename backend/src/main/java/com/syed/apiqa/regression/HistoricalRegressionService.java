package com.syed.apiqa.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HistoricalRegressionService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalRegressionService.class);
    private final TestRunRepository testRunRepository;
    private final PerformanceMetricRepository performanceMetricRepository;
    private final ExecutionRepository executionRepository;
    private final ApiEndpointRepository apiEndpointRepository;
    private final RegressionFindingRepository regressionFindingRepository;
    private final ObjectMapper objectMapper;

    public HistoricalRegressionService(TestRunRepository testRunRepository,
                                       PerformanceMetricRepository performanceMetricRepository,
                                       ExecutionRepository executionRepository,
                                       ApiEndpointRepository apiEndpointRepository,
                                       RegressionFindingRepository regressionFindingRepository,
                                       ObjectMapper objectMapper) {
        this.testRunRepository = testRunRepository;
        this.performanceMetricRepository = performanceMetricRepository;
        this.executionRepository = executionRepository;
        this.apiEndpointRepository = apiEndpointRepository;
        this.regressionFindingRepository = regressionFindingRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Default regression evaluation using the latest completed baseline for the same target OpenAPI.
     */
    @Transactional
    public RegressionReport evaluateRegression(TestRun currentRun) {
        return evaluateRegression(currentRun, currentRun.getBaselineRunId());
    }

    /**
     * Regression evaluation with support for explicit baseline selection.
     */
    @Transactional
    public RegressionReport evaluateRegression(TestRun currentRun, String explicitBaselineId) {
        TestRun baselineRun = null;

        if (explicitBaselineId != null && !explicitBaselineId.isBlank()) {
            baselineRun = testRunRepository.findById(explicitBaselineId).orElse(null);
            if (baselineRun != null && baselineRun.getId().equals(currentRun.getId())) {
                baselineRun = null; // Cannot compare run against itself
            }
        }

        if (baselineRun == null) {
            List<TestRun> pastRuns = testRunRepository.findByOrderByCreatedAtDesc();
            baselineRun = pastRuns.stream()
                    .filter(r -> !r.getId().equals(currentRun.getId()))
                    .filter(r -> r.getStatus() == RunStatus.COMPLETED)
                    .filter(r -> currentRun.getOpenapiUrl() != null && currentRun.getOpenapiUrl().equalsIgnoreCase(r.getOpenapiUrl()))
                    .findFirst()
                    .orElse(null);
        }

        if (baselineRun == null) {
            log.info("No baseline found for target {}. Establishing TestRun {} as initial baseline.",
                    currentRun.getOpenapiUrl(), currentRun.getId());
            RegressionReport baselineReport = new RegressionReport(
                    currentRun.getId(),
                    null,
                    "BASELINE_ESTABLISHED",
                    0.0, 0.0, 0.0, 0.0,
                    0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L,
                    0, 0, 0, 0,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    "First completed run for this specification. Baseline established."
            );
            persistRegressionReport(currentRun, null, baselineReport);
            return baselineReport;
        }

        log.info("Comparing TestRun {} against baseline TestRun {} (target: {})",
                currentRun.getId(), baselineRun.getId(), currentRun.getOpenapiUrl());

        List<RegressionFinding> findings = new ArrayList<>();

        // 1. Evaluate API Inventory Changes (Added / Removed APIs)
        evaluateApiInventoryDrift(currentRun, baselineRun, findings);

        // 2. Evaluate Functional & Contract Regressions (New Failures, Fixed Failures, Status Changes)
        List<ContractRegressionItem> contractRegressions = evaluateExecutionRegressions(currentRun, baselineRun, findings);

        // 3. Evaluate Latency Regressions across P50, P90, P95, P99 percentiles
        Optional<PerformanceMetric> currentPerfOpt = performanceMetricRepository.findByTestRunIdAndApiEndpointIsNull(currentRun.getId());
        Optional<PerformanceMetric> baselinePerfOpt = performanceMetricRepository.findByTestRunIdAndApiEndpointIsNull(baselineRun.getId());

        long currentP50 = currentPerfOpt.map(PerformanceMetric::getP50LatencyMs).orElse(0L);
        long currentP90 = currentPerfOpt.map(PerformanceMetric::getP90LatencyMs).orElse(0L);
        long currentP95 = currentPerfOpt.map(PerformanceMetric::getP95LatencyMs).orElse(0L);
        long currentP99 = currentPerfOpt.map(PerformanceMetric::getP99LatencyMs).orElse(0L);

        long baselineP50 = baselinePerfOpt.map(PerformanceMetric::getP50LatencyMs).orElse(0L);
        long baselineP90 = baselinePerfOpt.map(PerformanceMetric::getP90LatencyMs).orElse(0L);
        long baselineP95 = baselinePerfOpt.map(PerformanceMetric::getP95LatencyMs).orElse(0L);
        long baselineP99 = baselinePerfOpt.map(PerformanceMetric::getP99LatencyMs).orElse(0L);

        double deltaP50 = calcDelta(baselineP50, currentP50);
        double deltaP90 = calcDelta(baselineP90, currentP90);
        double deltaP95 = calcDelta(baselineP95, currentP95);
        double deltaP99 = calcDelta(baselineP99, currentP99);

        // Latency findings classification
        if (deltaP95 >= 50.0) {
            findings.add(new RegressionFinding(
                    currentRun, baselineRun,
                    RegressionFinding.FindingType.LATENCY_REGRESSION,
                    RegressionFinding.Severity.CRITICAL,
                    "GLOBAL", "SLA_P95",
                    baselineP95 + "ms", currentP95 + "ms",
                    String.format("Critical P95 latency degradation of +%.1f%% (from %dms to %dms).", deltaP95, baselineP95, currentP95)
            ));
        } else if (deltaP95 >= 25.0) {
            findings.add(new RegressionFinding(
                    currentRun, baselineRun,
                    RegressionFinding.FindingType.LATENCY_REGRESSION,
                    RegressionFinding.Severity.HIGH,
                    "GLOBAL", "SLA_P95",
                    baselineP95 + "ms", currentP95 + "ms",
                    String.format("High P95 latency increase of +%.1f%% (from %dms to %dms).", deltaP95, baselineP95, currentP95)
            ));
        }

        if (deltaP50 >= 35.0) {
            findings.add(new RegressionFinding(
                    currentRun, baselineRun,
                    RegressionFinding.FindingType.LATENCY_REGRESSION,
                    RegressionFinding.Severity.MEDIUM,
                    "GLOBAL", "MEDIAN_P50",
                    baselineP50 + "ms", currentP50 + "ms",
                    String.format("Median P50 latency increased by +%.1f%% (from %dms to %dms).", deltaP50, baselineP50, currentP50)
            ));
        }

        // Per-endpoint latency regression analysis
        List<EndpointRegressionItem> endpointRegressions = detectEndpointLatencyRegressions(currentRun.getId(), baselineRun.getId(), currentRun, baselineRun, findings);

        // 4. Counts & Summary
        int newFailures = (int) findings.stream().filter(f -> f.getFindingType() == RegressionFinding.FindingType.NEW_FAILURE).count();
        int fixedFailures = (int) findings.stream().filter(f -> f.getFindingType() == RegressionFinding.FindingType.FIXED_FAILURE).count();
        int addedApis = (int) findings.stream().filter(f -> f.getFindingType() == RegressionFinding.FindingType.API_ADDED).count();
        int removedApis = (int) findings.stream().filter(f -> f.getFindingType() == RegressionFinding.FindingType.API_REMOVED).count();

        String overallStatus = determineOverallStatus(findings, deltaP95);
        String summary = buildSummary(findings, deltaP95, baselineP95, currentP95, newFailures, fixedFailures, addedApis, removedApis);

        // 5. Persist Regression Findings in Database (clean prior findings for this run)
        regressionFindingRepository.deleteByTestRunId(currentRun.getId());
        regressionFindingRepository.saveAll(findings);

        List<RegressionFindingItem> findingItems = findings.stream().map(f -> new RegressionFindingItem(
                f.getFindingType().name(),
                f.getSeverity().name(),
                f.getHttpMethod(),
                f.getEndpointPath(),
                f.getBaselineValue(),
                f.getCurrentValue(),
                f.getDescription()
        )).collect(Collectors.toList());

        RegressionReport report = new RegressionReport(
                currentRun.getId(),
                baselineRun.getId(),
                overallStatus,
                deltaP50, deltaP90, deltaP95, deltaP99,
                currentP50, currentP90, currentP95, currentP99,
                baselineP50, baselineP90, baselineP95, baselineP99,
                newFailures, fixedFailures, addedApis, removedApis,
                contractRegressions,
                endpointRegressions,
                findingItems,
                summary
        );

        persistRegressionReport(currentRun, baselineRun.getId(), report);
        return report;
    }

    private void evaluateApiInventoryDrift(TestRun currentRun, TestRun baselineRun, List<RegressionFinding> findings) {
        List<ApiEndpoint> currentApis = apiEndpointRepository.findByTestRunId(currentRun.getId());
        List<ApiEndpoint> baselineApis = apiEndpointRepository.findByTestRunId(baselineRun.getId());

        Set<String> baselineKeys = baselineApis.stream()
                .map(a -> (a.getMethod() + " " + a.getPath()).toUpperCase())
                .collect(Collectors.toSet());

        Set<String> currentKeys = currentApis.stream()
                .map(a -> (a.getMethod() + " " + a.getPath()).toUpperCase())
                .collect(Collectors.toSet());

        // Added APIs
        for (ApiEndpoint api : currentApis) {
            String key = (api.getMethod() + " " + api.getPath()).toUpperCase();
            if (!baselineKeys.contains(key)) {
                findings.add(new RegressionFinding(
                        currentRun, baselineRun,
                        RegressionFinding.FindingType.API_ADDED,
                        RegressionFinding.Severity.LOW,
                        api.getMethod(), api.getPath(),
                        "NON_EXISTENT", "AVAILABLE",
                        "New API endpoint added: " + api.getMethod() + " " + api.getPath()
                ));
            }
        }

        // Removed APIs (Breaking Contract Change)
        for (ApiEndpoint api : baselineApis) {
            String key = (api.getMethod() + " " + api.getPath()).toUpperCase();
            if (!currentKeys.contains(key)) {
                findings.add(new RegressionFinding(
                        currentRun, baselineRun,
                        RegressionFinding.FindingType.API_REMOVED,
                        RegressionFinding.Severity.HIGH,
                        api.getMethod(), api.getPath(),
                        "AVAILABLE", "DELETED_OR_MISSING",
                        "API endpoint removed from specification: " + api.getMethod() + " " + api.getPath()
                ));
            }
        }
    }

    private List<ContractRegressionItem> evaluateExecutionRegressions(TestRun currentRun, TestRun baselineRun,
                                                                      List<RegressionFinding> findings) {
        List<Execution> currentExecutions = executionRepository.findByTestRunId(currentRun.getId());
        List<Execution> baselineExecutions = executionRepository.findByTestRunId(baselineRun.getId());

        Map<String, Execution> baselineMap = new HashMap<>();
        for (Execution be : baselineExecutions) {
            if (be.getTestStep() != null) {
                String key = be.getTestStep().getMethod().toUpperCase() + " " + be.getTestStep().getPathTemplate();
                baselineMap.put(key, be);
            }
        }

        List<ContractRegressionItem> contractItems = new ArrayList<>();

        for (Execution ce : currentExecutions) {
            if (ce.getTestStep() == null) continue;
            String key = ce.getTestStep().getMethod().toUpperCase() + " " + ce.getTestStep().getPathTemplate();
            Execution be = baselineMap.get(key);

            if (be != null) {
                Integer bStatus = be.getResponseStatus();
                Integer cStatus = ce.getResponseStatus();
                String method = ce.getTestStep().getMethod();
                String path = ce.getTestStep().getPathTemplate();

                // 1. New Failure
                if (be.getStatus() == StepStatus.PASSED && ce.getStatus() != StepStatus.PASSED) {
                    RegressionFinding.Severity severity = (cStatus != null && cStatus >= 500)
                            || ce.getStatus() == StepStatus.TIMEOUT
                            ? RegressionFinding.Severity.CRITICAL
                            : RegressionFinding.Severity.HIGH;

                    String desc = String.format("Endpoint previously passed with HTTP %s, but now failed (%s) with HTTP %s",
                            bStatus != null ? bStatus : "2xx", ce.getStatus(), cStatus != null ? cStatus : "N/A");

                    findings.add(new RegressionFinding(
                            currentRun, baselineRun,
                            RegressionFinding.FindingType.NEW_FAILURE,
                            severity, method, path,
                            String.valueOf(bStatus), String.valueOf(cStatus),
                            desc
                    ));

                    contractItems.add(new ContractRegressionItem(key, "NEW_STEP_FAILURE", String.valueOf(bStatus), String.valueOf(cStatus), desc));
                }

                // 2. Fixed Failure (Improvement)
                if (be.getStatus() != StepStatus.PASSED && ce.getStatus() == StepStatus.PASSED) {
                    String desc = String.format("Endpoint previously failed with HTTP %s, but now PASSED with HTTP %s",
                            bStatus != null ? bStatus : "FAIL", cStatus != null ? cStatus : "200");

                    findings.add(new RegressionFinding(
                            currentRun, baselineRun,
                            RegressionFinding.FindingType.FIXED_FAILURE,
                            RegressionFinding.Severity.LOW, method, path,
                            String.valueOf(bStatus), String.valueOf(cStatus),
                            desc
                    ));
                }

                // 3. HTTP Status Code Shift
                if (bStatus != null && cStatus != null && !bStatus.equals(cStatus)) {
                    RegressionFinding.Severity severity;
                    if (cStatus >= 500) {
                        severity = RegressionFinding.Severity.CRITICAL;
                    } else if (cStatus >= 400 && bStatus < 400) {
                        severity = RegressionFinding.Severity.HIGH;
                    } else {
                        severity = RegressionFinding.Severity.MEDIUM;
                    }

                    String desc = String.format("HTTP response status shifted from %d to %d", bStatus, cStatus);
                    findings.add(new RegressionFinding(
                            currentRun, baselineRun,
                            RegressionFinding.FindingType.STATUS_CHANGED,
                            severity, method, path,
                            String.valueOf(bStatus), String.valueOf(cStatus),
                            desc
                    ));
                }
            }
        }
        return contractItems;
    }

    private List<EndpointRegressionItem> detectEndpointLatencyRegressions(String currentRunId, String baselineRunId,
                                                                         TestRun currentRun, TestRun baselineRun,
                                                                         List<RegressionFinding> findings) {
        List<PerformanceMetric> currentMetrics = performanceMetricRepository.findByTestRunId(currentRunId);
        List<PerformanceMetric> baselineMetrics = performanceMetricRepository.findByTestRunId(baselineRunId);

        Map<String, PerformanceMetric> baselineMap = new HashMap<>();
        for (PerformanceMetric bm : baselineMetrics) {
            if (bm.getApiEndpoint() != null) {
                baselineMap.put(bm.getApiEndpoint().getId(), bm);
            }
        }

        List<EndpointRegressionItem> items = new ArrayList<>();
        for (PerformanceMetric cm : currentMetrics) {
            if (cm.getApiEndpoint() == null) continue;
            PerformanceMetric bm = baselineMap.get(cm.getApiEndpoint().getId());
            if (bm != null && bm.getP95LatencyMs() > 0) {
                long cP95 = cm.getP95LatencyMs();
                long bP95 = bm.getP95LatencyMs();
                double delta = calcDelta(bP95, cP95);

                if (delta >= 25.0) {
                    RegressionFinding.Severity severity = delta >= 50.0 ? RegressionFinding.Severity.CRITICAL : RegressionFinding.Severity.HIGH;
                    items.add(new EndpointRegressionItem(
                            cm.getApiEndpoint().getMethod(),
                            cm.getApiEndpoint().getPath(),
                            bP95, cP95, delta, severity.name()
                    ));

                    findings.add(new RegressionFinding(
                            currentRun, baselineRun,
                            RegressionFinding.FindingType.LATENCY_REGRESSION,
                            severity,
                            cm.getApiEndpoint().getMethod(), cm.getApiEndpoint().getPath(),
                            bP95 + "ms", cP95 + "ms",
                            String.format("Endpoint %s %s P95 latency increased by +%.1f%% (from %dms to %dms)",
                                    cm.getApiEndpoint().getMethod(), cm.getApiEndpoint().getPath(), delta, bP95, cP95)
                    ));
                }
            }
        }
        return items;
    }

    private double calcDelta(long baseline, long current) {
        if (baseline <= 0) return 0.0;
        double delta = ((double) (current - baseline) / baseline) * 100.0;
        return Math.round(delta * 10.0) / 10.0;
    }

    private String determineOverallStatus(List<RegressionFinding> findings, double deltaP95) {
        boolean hasCritical = findings.stream().anyMatch(f -> f.getSeverity() == RegressionFinding.Severity.CRITICAL);
        boolean hasHigh = findings.stream().anyMatch(f -> f.getSeverity() == RegressionFinding.Severity.HIGH);

        if (hasCritical || deltaP95 >= 50.0) return "CRITICAL_REGRESSION";
        if (hasHigh || deltaP95 >= 25.0) return "HIGH_REGRESSION";
        if (!findings.isEmpty()) return "MINOR_REGRESSION";
        return "NO_REGRESSION";
    }

    private String buildSummary(List<RegressionFinding> findings, double deltaP95, long baselineP95, long currentP95,
                                int newFailures, int fixedFailures, int addedApis, int removedApis) {
        StringBuilder sb = new StringBuilder();
        if (newFailures > 0) sb.append(String.format("Found %d new failure(s). ", newFailures));
        if (fixedFailures > 0) sb.append(String.format("%d prior failure(s) now fixed. ", fixedFailures));
        if (removedApis > 0) sb.append(String.format("%d API endpoint(s) removed. ", removedApis));
        if (addedApis > 0) sb.append(String.format("%d API endpoint(s) added. ", addedApis));

        if (deltaP95 >= 25.0) {
            sb.append(String.format("P95 latency increased by +%.1f%% (from %dms to %dms). ", deltaP95, baselineP95, currentP95));
        } else if (deltaP95 <= -10.0) {
            sb.append(String.format("P95 latency improved by %.1f%% (from %dms to %dms). ", Math.abs(deltaP95), baselineP95, currentP95));
        } else {
            sb.append(String.format("P95 latency stable (%dms vs %dms). ", currentP95, baselineP95));
        }
        return sb.toString().trim();
    }

    private void persistRegressionReport(TestRun currentRun, String baselineRunId, RegressionReport report) {
        try {
            currentRun.setBaselineRunId(baselineRunId);
            currentRun.setRegressionSummaryJson(objectMapper.writeValueAsString(report));
            testRunRepository.save(currentRun);
        } catch (Exception e) {
            log.warn("Failed to persist regression summary JSON for run {}", currentRun.getId(), e);
        }
    }

    // ------------------------------------------------------------------ DTOs

    public static class RegressionReport {
        private final String currentRunId;
        private final String baselineRunId;
        private final String status;
        private final double p50DeltaPercent;
        private final double p90DeltaPercent;
        private final double p95DeltaPercent;
        private final double p99DeltaPercent;
        private final long currentP50Ms;
        private final long currentP90Ms;
        private final long currentP95Ms;
        private final long currentP99Ms;
        private final long baselineP50Ms;
        private final long baselineP90Ms;
        private final long baselineP95Ms;
        private final long baselineP99Ms;
        private final int newFailuresCount;
        private final int fixedFailuresCount;
        private final int addedApisCount;
        private final int removedApisCount;
        private final List<ContractRegressionItem> contractRegressions;
        private final List<EndpointRegressionItem> endpointRegressions;
        private final List<RegressionFindingItem> findings;
        private final String summary;

        public RegressionReport(String currentRunId, String baselineRunId, String status,
                                double p50DeltaPercent, double p90DeltaPercent, double p95DeltaPercent, double p99DeltaPercent,
                                long currentP50Ms, long currentP90Ms, long currentP95Ms, long currentP99Ms,
                                long baselineP50Ms, long baselineP90Ms, long baselineP95Ms, long baselineP99Ms,
                                int newFailuresCount, int fixedFailuresCount, int addedApisCount, int removedApisCount,
                                List<ContractRegressionItem> contractRegressions,
                                List<EndpointRegressionItem> endpointRegressions,
                                List<RegressionFindingItem> findings,
                                String summary) {
            this.currentRunId = currentRunId;
            this.baselineRunId = baselineRunId;
            this.status = status;
            this.p50DeltaPercent = p50DeltaPercent;
            this.p90DeltaPercent = p90DeltaPercent;
            this.p95DeltaPercent = p95DeltaPercent;
            this.p99DeltaPercent = p99DeltaPercent;
            this.currentP50Ms = currentP50Ms;
            this.currentP90Ms = currentP90Ms;
            this.currentP95Ms = currentP95Ms;
            this.currentP99Ms = currentP99Ms;
            this.baselineP50Ms = baselineP50Ms;
            this.baselineP90Ms = baselineP90Ms;
            this.baselineP95Ms = baselineP95Ms;
            this.baselineP99Ms = baselineP99Ms;
            this.newFailuresCount = newFailuresCount;
            this.fixedFailuresCount = fixedFailuresCount;
            this.addedApisCount = addedApisCount;
            this.removedApisCount = removedApisCount;
            this.contractRegressions = contractRegressions;
            this.endpointRegressions = endpointRegressions;
            this.findings = findings;
            this.summary = summary;
        }

        public String getCurrentRunId() { return currentRunId; }
        public String getBaselineRunId() { return baselineRunId; }
        public String getStatus() { return status; }
        public double getP50DeltaPercent() { return p50DeltaPercent; }
        public double getP90DeltaPercent() { return p90DeltaPercent; }
        public double getP95DeltaPercent() { return p95DeltaPercent; }
        public double getP99DeltaPercent() { return p99DeltaPercent; }
        public long getCurrentP50Ms() { return currentP50Ms; }
        public long getCurrentP90Ms() { return currentP90Ms; }
        public long getCurrentP95Ms() { return currentP95Ms; }
        public long getCurrentP99Ms() { return currentP99Ms; }
        public long getBaselineP50Ms() { return baselineP50Ms; }
        public long getBaselineP90Ms() { return baselineP90Ms; }
        public long getBaselineP95Ms() { return baselineP95Ms; }
        public long getBaselineP99Ms() { return baselineP99Ms; }
        public int getNewFailuresCount() { return newFailuresCount; }
        public int getFixedFailuresCount() { return fixedFailuresCount; }
        public int getAddedApisCount() { return addedApisCount; }
        public int getRemovedApisCount() { return removedApisCount; }
        public List<ContractRegressionItem> getContractRegressions() { return contractRegressions; }
        public List<EndpointRegressionItem> getEndpointRegressions() { return endpointRegressions; }
        public List<RegressionFindingItem> getFindings() { return findings; }
        public String getSummary() { return summary; }
    }

    public static class RegressionFindingItem {
        private final String findingType;
        private final String severity;
        private final String httpMethod;
        private final String endpointPath;
        private final String baselineValue;
        private final String currentValue;
        private final String description;

        public RegressionFindingItem(String findingType, String severity, String httpMethod,
                                     String endpointPath, String baselineValue, String currentValue,
                                     String description) {
            this.findingType = findingType;
            this.severity = severity;
            this.httpMethod = httpMethod;
            this.endpointPath = endpointPath;
            this.baselineValue = baselineValue;
            this.currentValue = currentValue;
            this.description = description;
        }

        public String getFindingType() { return findingType; }
        public String getSeverity() { return severity; }
        public String getHttpMethod() { return httpMethod; }
        public String getEndpointPath() { return endpointPath; }
        public String getBaselineValue() { return baselineValue; }
        public String getCurrentValue() { return currentValue; }
        public String getDescription() { return description; }
    }

    public static class ContractRegressionItem {
        private final String endpoint;
        private final String regressionType;
        private final String baselineOutcome;
        private final String currentOutcome;
        private final String description;

        public ContractRegressionItem(String endpoint, String regressionType, String baselineOutcome,
                                      String currentOutcome, String description) {
            this.endpoint = endpoint;
            this.regressionType = regressionType;
            this.baselineOutcome = baselineOutcome;
            this.currentOutcome = currentOutcome;
            this.description = description;
        }

        public String getEndpoint() { return endpoint; }
        public String getRegressionType() { return regressionType; }
        public String getBaselineOutcome() { return baselineOutcome; }
        public String getCurrentOutcome() { return currentOutcome; }
        public String getDescription() { return description; }
    }

    public static class EndpointRegressionItem {
        private final String method;
        private final String path;
        private final long baselineP95Ms;
        private final long currentP95Ms;
        private final double deltaPercent;
        private final String severity;

        public EndpointRegressionItem(String method, String path, long baselineP95Ms,
                                      long currentP95Ms, double deltaPercent, String severity) {
            this.method = method;
            this.path = path;
            this.baselineP95Ms = baselineP95Ms;
            this.currentP95Ms = currentP95Ms;
            this.deltaPercent = deltaPercent;
            this.severity = severity;
        }

        public String getMethod() { return method; }
        public String getPath() { return path; }
        public long getBaselineP95Ms() { return baselineP95Ms; }
        public long getCurrentP95Ms() { return currentP95Ms; }
        public double getDeltaPercent() { return deltaPercent; }
        public String getSeverity() { return severity; }
    }
}
