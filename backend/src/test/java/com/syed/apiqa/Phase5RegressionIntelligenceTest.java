package com.syed.apiqa;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.syed.apiqa.api.TestRunController;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.regression.HistoricalRegressionService;
import com.syed.apiqa.run.RunManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase5RegressionIntelligenceTest {

    private static WireMockServer wireMockServer;
    private static String baseUrl;

    @Autowired
    private RunManager runManager;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private RegressionFindingRepository regressionFindingRepository;

    @Autowired
    private HistoricalRegressionService regressionService;

    @Autowired
    private TestRunController testRunController;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        String specV1 = "{\n" +
                "  \"openapi\": \"3.0.1\",\n" +
                "  \"info\": { \"title\": \"Regression API\", \"version\": \"1.0.0\" },\n" +
                "  \"servers\": [{ \"url\": \"" + baseUrl + "\" }],\n" +
                "  \"paths\": {\n" +
                "    \"/items\": {\n" +
                "      \"get\": { \"summary\": \"List Items\", \"responses\": { \"200\": { \"description\": \"OK\" } } }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(specV1)));

        wireMockServer.stubFor(get(urlEqualTo("/items"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(20)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"item_1\",\"name\":\"widget\"}]")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testEndToEndRegressionAnalysisWithFindingsAndPersistence() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        // =====================================================================
        // 1. RUN 1: Baseline Execution (Healthy, Fast)
        // =====================================================================
        TestRun baselineRun = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        baselineRun.setOwnerId("user_reg_owner");
        testRunRepository.save(baselineRun);

        runManager.executeRunAsync(baselineRun.getId(), "NONE", null);
        waitForCompletion(baselineRun.getId());

        TestRun completedBaseline = testRunRepository.findById(baselineRun.getId()).orElseThrow();
        assertEquals(RunStatus.COMPLETED, completedBaseline.getStatus());

        // First run establishes initial baseline
        HistoricalRegressionService.RegressionReport report1 = regressionService.evaluateRegression(completedBaseline);
        assertEquals("BASELINE_ESTABLISHED", report1.getStatus());

        // =====================================================================
        // 2. RUN 2: Comparative Run with Injected Drift & Latency Degradation
        // =====================================================================
        // Inject latency on /items (200ms delay -> massive P95 increase)
        wireMockServer.stubFor(get(urlEqualTo("/items"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(500)
                        .withFixedDelay(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Database connection pool exhausted\"}")));

        TestRun currentRun = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        currentRun.setOwnerId("user_reg_owner");
        testRunRepository.save(currentRun);

        runManager.executeRunAsync(currentRun.getId(), "NONE", null);
        waitForCompletion(currentRun.getId());

        TestRun completedCurrent = testRunRepository.findById(currentRun.getId()).orElseThrow();
        assertEquals(RunStatus.COMPLETED, completedCurrent.getStatus());

        // Evaluate regression against baseline
        HistoricalRegressionService.RegressionReport report2 = regressionService.evaluateRegression(completedCurrent, completedBaseline.getId());
        assertNotNull(report2);
        assertEquals(completedBaseline.getId(), report2.getBaselineRunId());
        assertTrue(report2.getStatus().contains("REGRESSION"));
        assertTrue(report2.getP95DeltaPercent() > 25.0);

        // =====================================================================
        // 3. Verify Database Persistence of Findings
        // =====================================================================
        List<RegressionFinding> persistedFindings = regressionFindingRepository.findByTestRunIdOrderByCreatedAtDesc(completedCurrent.getId());
        assertFalse(persistedFindings.isEmpty(), "Regression findings must be persisted to database");

        boolean hasNewFailure = persistedFindings.stream()
                .anyMatch(f -> f.getFindingType() == RegressionFinding.FindingType.NEW_FAILURE
                        && f.getSeverity() == RegressionFinding.Severity.CRITICAL);
        assertTrue(hasNewFailure, "Must detect NEW_FAILURE with CRITICAL severity for 500 error");

        boolean hasLatencyRegression = persistedFindings.stream()
                .anyMatch(f -> f.getFindingType() == RegressionFinding.FindingType.LATENCY_REGRESSION);
        assertTrue(hasLatencyRegression, "Must record latency degradation finding");

        // =====================================================================
        // 4. Test Authorized Regression Endpoints
        // =====================================================================
        // Authorized owner accessing GET /api/runs/{id}/regression
        ResponseEntity<?> authorizedRes = testRunController.getRegressionSummary(completedCurrent.getId(), "user_reg_owner", null);
        assertEquals(200, authorizedRes.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) authorizedRes.getBody();
        assertNotNull(body);
        assertEquals(completedCurrent.getId(), body.get("runId"));
        assertNotNull(body.get("findings"));

        // Unauthorized user blocked with 403 Forbidden
        ResponseEntity<?> unauthorizedRes = testRunController.getRegressionSummary(completedCurrent.getId(), "attacker_user", null);
        assertEquals(403, unauthorizedRes.getStatusCode().value());

        // Unauthenticated request on owned run blocked with 401 Unauthorized
        ResponseEntity<?> unauthenticatedRes = testRunController.getRegressionSummary(completedCurrent.getId(), null, null);
        assertEquals(401, unauthenticatedRes.getStatusCode().value());

        // Available Baselines endpoint
        ResponseEntity<?> baselinesRes = testRunController.getAvailableBaselines(completedCurrent.getId(), "user_reg_owner", null);
        assertEquals(200, baselinesRes.getStatusCode().value());
        List<?> candidateList = (List<?>) baselinesRes.getBody();
        assertNotNull(candidateList);
        assertFalse(candidateList.isEmpty(), "Candidate baseline list must contain the prior completed run");

        // Explicit baseline comparison via POST /api/runs/{id}/regression/compare
        ResponseEntity<?> compareRes = testRunController.compareWithBaseline(completedCurrent.getId(), completedBaseline.getId(), "user_reg_owner", null);
        assertEquals(200, compareRes.getStatusCode().value());
        Map<String, Object> compareBody = (Map<String, Object>) compareRes.getBody();
        assertNotNull(compareBody.get("report"));
    }

    private void waitForCompletion(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 40000;
        while (System.currentTimeMillis() < deadline) {
            TestRun run = testRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED) {
                return;
            }
            Thread.sleep(100);
        }
        fail("TestRun " + runId + " did not reach COMPLETED within 40 seconds");
    }
}
