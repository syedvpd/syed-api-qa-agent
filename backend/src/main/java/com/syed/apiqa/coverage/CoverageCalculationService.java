package com.syed.apiqa.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.EndpointCoverageRepository;
import com.syed.apiqa.persistence.TestRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Pure deterministic, zero-LLM API QA Coverage calculation and Behavior Classification engine.
 * Computes deterministic coverage metrics, classifies endpoints (FULL, PARTIAL, BLOCKED, UNSUPPORTED),
 * and persists verifiable coverage evidence.
 */
@Service
public class CoverageCalculationService {

    private static final Logger log = LoggerFactory.getLogger(CoverageCalculationService.class);

    private final EndpointCoverageRepository endpointCoverageRepository;
    private final TestRunRepository testRunRepository;
    private final com.syed.apiqa.persistence.TestCaseRepository testCaseRepository;
    private final ObjectMapper objectMapper;

    public CoverageCalculationService(EndpointCoverageRepository endpointCoverageRepository,
                                      TestRunRepository testRunRepository,
                                      com.syed.apiqa.persistence.TestCaseRepository testCaseRepository,
                                      ObjectMapper objectMapper) {
        this.endpointCoverageRepository = endpointCoverageRepository;
        this.testRunRepository = testRunRepository;
        this.testCaseRepository = testCaseRepository;
        this.objectMapper = objectMapper;
    }

    public static class CoverageSummary {
        private int totalDiscovered;
        private int tested;
        private int fullyTested;
        private int partiallyTested;
        private int blocked;
        private int unsupported;
        private double crudCoveragePercent;
        private double negativeCoveragePercent;
        private double contractAssertionCoveragePercent;
        private double qaCoverageScore;

        public int getTotalDiscovered() { return totalDiscovered; }
        public void setTotalDiscovered(int totalDiscovered) { this.totalDiscovered = totalDiscovered; }

        public int getTested() { return tested; }
        public void setTested(int tested) { this.tested = tested; }

        public int getFullyTested() { return fullyTested; }
        public void setFullyTested(int fullyTested) { this.fullyTested = fullyTested; }

        public int getPartiallyTested() { return partiallyTested; }
        public void setPartiallyTested(int partiallyTested) { this.partiallyTested = partiallyTested; }

        public int getBlocked() { return blocked; }
        public void setBlocked(int blocked) { this.blocked = blocked; }

        public int getUnsupported() { return unsupported; }
        public void setUnsupported(int unsupported) { this.unsupported = unsupported; }

        public double getCrudCoveragePercent() { return crudCoveragePercent; }
        public void setCrudCoveragePercent(double crudCoveragePercent) { this.crudCoveragePercent = crudCoveragePercent; }

        public double getNegativeCoveragePercent() { return negativeCoveragePercent; }
        public void setNegativeCoveragePercent(double negativeCoveragePercent) { this.negativeCoveragePercent = negativeCoveragePercent; }

        public double getContractAssertionCoveragePercent() { return contractAssertionCoveragePercent; }
        public void setContractAssertionCoveragePercent(double contractAssertionCoveragePercent) { this.contractAssertionCoveragePercent = contractAssertionCoveragePercent; }

        public double getQaCoverageScore() { return qaCoverageScore; }
        public void setQaCoverageScore(double qaCoverageScore) { this.qaCoverageScore = qaCoverageScore; }
    }

    @Transactional
    public CoverageSummary calculateAndPersistCoverage(TestRun run,
                                                        List<ApiEndpoint> endpoints,
                                                        List<TestStep> allSteps,
                                                        List<AssertionResult> allAssertions) {

        log.info("Calculating deterministic API QA Coverage for TestRun {}", run.getId());
        endpointCoverageRepository.deleteByTestRunId(run.getId());

        Map<String, List<TestStep>> stepsByEndpointKey = new HashMap<>();
        for (TestStep step : allSteps) {
            String key = step.getMethod().toUpperCase() + ":" + step.getPathTemplate();
            stepsByEndpointKey.computeIfAbsent(key, k -> new ArrayList<>()).add(step);
        }

        Map<String, TestCase> testCasesById = new HashMap<>();
        try {
            for (TestCase tc : testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(run.getId())) {
                testCasesById.put(tc.getId(), tc);
            }
        } catch (Exception ignored) {}

        int fullyTestedCount = 0;
        int partiallyTestedCount = 0;
        int blockedCount = 0;
        int unsupportedCount = 0;
        int crudTestedEndpoints = 0;
        int negativeTestedEndpoints = 0;
        int contractValidatedEndpoints = 0;

        List<EndpointCoverage> coverages = new ArrayList<>();

        for (ApiEndpoint ep : endpoints) {
            String key = ep.getMethod().toUpperCase() + ":" + ep.getPath();
            List<TestStep> steps = stepsByEndpointKey.getOrDefault(key, Collections.emptyList());

            EndpointCoverage.Classification classification;
            String reason;
            boolean crudTested = false;
            boolean negativeTested = false;
            boolean contractValidated = false;
            int assertionCount = 0;

            if (run.getEnvironmentType() == EnvironmentType.PRODUCTION && "DELETE".equalsIgnoreCase(ep.getMethod())) {
                classification = EndpointCoverage.Classification.BLOCKED;
                reason = "Destructive DELETE operation disabled by production safety policy.";
                blockedCount++;
            } else if (steps.isEmpty()) {
                classification = EndpointCoverage.Classification.UNSUPPORTED;
                reason = "Endpoint not exercisable: missing path parameters or unsupported protocol.";
                unsupportedCount++;
            } else {
                boolean anyPassed = steps.stream().anyMatch(s -> s.getStatus() == StepStatus.PASSED);
                boolean allBlocked = steps.stream().allMatch(s -> s.getStatus() == StepStatus.BLOCKED || s.getStatus() == StepStatus.SKIPPED);

                for (TestStep s : steps) {
                    TestCase tc = null;
                    try {
                        if (s.getTestCase() != null) {
                            tc = testCasesById.get(s.getTestCase().getId());
                        }
                    } catch (Exception ignored) {}
                    if (tc != null) {
                        String type = tc.getScenarioType();
                        if ("CRUD_WORKFLOW".equals(type) || "POSITIVE_CRUD".equals(tc.getCategory())) crudTested = true;
                        if ("NEGATIVE_ROBUSTNESS".equals(type) || "NEGATIVE_VALIDATION".equals(tc.getCategory())) negativeTested = true;
                    }
                }

                contractValidated = anyPassed;
                assertionCount = steps.size();

                if (crudTested) crudTestedEndpoints++;
                if (negativeTested) negativeTestedEndpoints++;
                if (contractValidated) contractValidatedEndpoints++;

                if (allBlocked) {
                    classification = EndpointCoverage.Classification.BLOCKED;
                    reason = "All targeted test steps were blocked due to upstream prerequisite failures.";
                    blockedCount++;
                } else if (anyPassed && (crudTested || negativeTested)) {
                    classification = EndpointCoverage.Classification.FULL;
                    reason = "Full coverage: Successful execution, contract validation, and robustness tests verified.";
                    fullyTestedCount++;
                } else if (anyPassed) {
                    classification = EndpointCoverage.Classification.PARTIAL;
                    reason = "Partial coverage: Status code verified, but lacks multi-step CRUD lifecycle or negative fuzzing.";
                    partiallyTestedCount++;
                } else {
                    classification = EndpointCoverage.Classification.PARTIAL;
                    reason = "Partial coverage: Executed but failed contract assertions.";
                    partiallyTestedCount++;
                }
            }

            EndpointCoverage ec = new EndpointCoverage(run, ep.getMethod(), ep.getPath(), classification, reason);
            ec.setCrudTested(crudTested);
            ec.setNegativeTested(negativeTested);
            ec.setContractValidated(contractValidated);
            ec.setAssertionsCount(assertionCount);
            coverages.add(ec);
        }

        endpointCoverageRepository.saveAll(coverages);

        int total = endpoints.size();
        int tested = fullyTestedCount + partiallyTestedCount;
        int effectiveTotal = Math.max(1, total - (run.getEnvironmentType() == EnvironmentType.PRODUCTION ? blockedCount : 0));

        // Formula: Score = ( (Full * 1.0) + (Partial * 0.5) ) / EffectiveTotal * 100
        double rawScore = ((fullyTestedCount * 1.0) + (partiallyTestedCount * 0.5)) / (double) effectiveTotal * 100.0;
        double qaCoverageScore = BigDecimal.valueOf(Math.min(100.0, Math.max(0.0, rawScore)))
                .setScale(2, RoundingMode.HALF_UP).doubleValue();

        CoverageSummary summary = new CoverageSummary();
        summary.setTotalDiscovered(total);
        summary.setTested(tested);
        summary.setFullyTested(fullyTestedCount);
        summary.setPartiallyTested(partiallyTestedCount);
        summary.setBlocked(blockedCount);
        summary.setUnsupported(unsupportedCount);
        summary.setQaCoverageScore(qaCoverageScore);

        if (total > 0) {
            summary.setCrudCoveragePercent(BigDecimal.valueOf((crudTestedEndpoints * 100.0) / total).setScale(1, RoundingMode.HALF_UP).doubleValue());
            summary.setNegativeCoveragePercent(BigDecimal.valueOf((negativeTestedEndpoints * 100.0) / total).setScale(1, RoundingMode.HALF_UP).doubleValue());
            summary.setContractAssertionCoveragePercent(BigDecimal.valueOf((contractValidatedEndpoints * 100.0) / total).setScale(1, RoundingMode.HALF_UP).doubleValue());
        }

        try {
            String json = objectMapper.writeValueAsString(summary);
            run.setCoverageScore(qaCoverageScore);
            run.setCoverageSummaryJson(json);
            testRunRepository.save(run);
        } catch (Exception e) {
            log.error("Failed to serialize coverage summary: {}", e.getMessage());
        }

        log.info("API QA Coverage calculated: Score={}% (Full={}, Partial={}, Blocked={}, Unsupported={})",
                qaCoverageScore, fullyTestedCount, partiallyTestedCount, blockedCount, unsupportedCount);

        return summary;
    }
}
