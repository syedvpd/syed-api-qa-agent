package com.syed.apiqa;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.performance.PerformanceAnalyticsService;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.regression.HistoricalRegressionService;
import com.syed.apiqa.run.RunManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase3PerformanceAndRegressionTest {

    private static WireMockServer wireMockServer;
    private static String baseUrl;

    @Autowired
    private RunManager runManager;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private PerformanceMetricRepository performanceMetricRepository;

    @Autowired
    private HistoricalRegressionService historicalRegressionService;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        String openApiJson = "{\n" +
                "  \"openapi\": \"3.0.1\",\n" +
                "  \"info\": { \"title\": \"Phase 3 Performance & Regression API\", \"version\": \"3.0.0\" },\n" +
                "  \"servers\": [{ \"url\": \"" + baseUrl + "\" }],\n" +
                "  \"paths\": {\n" +
                "    \"/products\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"List Products\",\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      },\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"Create Product\",\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\n" +
                "                \"type\": \"object\",\n" +
                "                \"required\": [\"name\", \"price\"],\n" +
                "                \"properties\": {\n" +
                "                  \"name\": { \"type\": \"string\" },\n" +
                "                  \"price\": { \"type\": \"number\", \"minimum\": 1 }\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"responses\": { \"201\": { \"description\": \"Created\" }, \"422\": { \"description\": \"Validation error\" } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/products/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Get Product by ID\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(openApiJson)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testStatisticalPercentileCalculation() {
        // Test mathematical nearest-rank percentile algorithm
        List<Long> values = Arrays.asList(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertEquals(50L, PerformanceAnalyticsService.calculatePercentile(values, 50), "P50 median");
        assertEquals(90L, PerformanceAnalyticsService.calculatePercentile(values, 90), "P90");
        assertEquals(100L, PerformanceAnalyticsService.calculatePercentile(values, 95), "P95");
        assertEquals(100L, PerformanceAnalyticsService.calculatePercentile(values, 99), "P99");
        assertEquals(10L, PerformanceAnalyticsService.calculatePercentile(values, 0), "Lower bound");
        assertEquals(100L, PerformanceAnalyticsService.calculatePercentile(values, 100), "Upper bound");

        // Edge case: single item list
        assertEquals(42L, PerformanceAnalyticsService.calculatePercentile(List.of(42L), 95));

        // Edge case: empty list
        assertEquals(0L, PerformanceAnalyticsService.calculatePercentile(Collections.emptyList(), 95));
    }

    @Test
    void testPerformanceAnalyticsAndMultiRunHistoricalRegression() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        // =====================================================================
        // RUN 1: Baseline Execution (Healthy, Fast)
        // =====================================================================
        wireMockServer.stubFor(get(urlEqualTo("/products"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(20)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"prod_1\",\"name\":\"Widget\",\"price\":19.99}]")));

        wireMockServer.stubFor(post(urlEqualTo("/products"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.name", matching("^[a-zA-Z0-9_ ]+$")))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withFixedDelay(20)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"prod_1\",\"name\":\"Widget\",\"price\":19.99}")));

        wireMockServer.stubFor(post(urlEqualTo("/products"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Invalid payload\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/products/prod_1"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(20)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"prod_1\",\"name\":\"Widget\",\"price\":19.99}")));

        TestRun run1 = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        testRunRepository.save(run1);

        runManager.executeRunAsync(run1.getId(), "NONE", null);
        waitForCompletion(run1.getId());

        TestRun completedRun1 = testRunRepository.findById(run1.getId()).orElseThrow();
        assertEquals(RunStatus.COMPLETED, completedRun1.getStatus());

        // Verify Run 1 Performance Metrics Persisted
        List<PerformanceMetric> run1Metrics = performanceMetricRepository.findByTestRunId(run1.getId());
        assertFalse(run1Metrics.isEmpty(), "Performance metrics must be persisted for Run 1");

        PerformanceMetric run1Overall = performanceMetricRepository.findByTestRunIdAndApiEndpointIsNull(run1.getId()).orElseThrow();
        assertTrue(run1Overall.getTotalSamples() > 0, "Samples must be recorded");
        assertTrue(run1Overall.getP95LatencyMs() >= 15L, "P95 latency should account for wiremock delay");

        // Verify Run 1 Baseline Report
        assertNotNull(completedRun1.getRegressionSummaryJson());
        assertTrue(completedRun1.getRegressionSummaryJson().contains("BASELINE_ESTABLISHED"));

        // =====================================================================
        // RUN 2: Injected Regression (Latency Degradation + Contract Failure)
        // =====================================================================
        // Inject latency on /products (200ms delay -> massive P95 increase)
        wireMockServer.stubFor(get(urlEqualTo("/products"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"prod_1\",\"name\":\"Widget\",\"price\":19.99}]")));

        // Inject 500 error on /products/prod_1 (previously returned 200!)
        wireMockServer.stubFor(get(urlEqualTo("/products/prod_1"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Database connection pool exhausted\"}")));

        TestRun run2 = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        testRunRepository.save(run2);

        runManager.executeRunAsync(run2.getId(), "NONE", null);
        waitForCompletion(run2.getId());

        TestRun completedRun2 = testRunRepository.findById(run2.getId()).orElseThrow();
        assertEquals(RunStatus.COMPLETED, completedRun2.getStatus());

        // Verify Historical Regression Detection
        assertEquals(run1.getId(), completedRun2.getBaselineRunId(), "Run 2 must link to Run 1 as baseline");
        assertNotNull(completedRun2.getRegressionSummaryJson(), "Regression summary JSON must be populated");

        HistoricalRegressionService.RegressionReport report = historicalRegressionService.evaluateRegression(completedRun2);
        assertNotNull(report);
        assertEquals(run1.getId(), report.getBaselineRunId());

        // Contract drift: GET /products/{id} previously passed with 200, now failed with 500
        assertFalse(report.getContractRegressions().isEmpty(), "Contract regression must detect step failure");
        assertTrue(report.getContractRegressions().stream().anyMatch(cr -> cr.getEndpoint().contains("/products")),
                "Contract regression must identify the failing /products endpoint");

        // Latency regression: P95 should show significant increase
        assertTrue(report.getP95DeltaPercent() > 25.0, "P95 delta should exceed +25% threshold due to 200ms delay");
        assertTrue(report.getStatus().contains("REGRESSION"), "Status must flag REGRESSION");
    }

    private void waitForCompletion(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < deadline) {
            TestRun run = testRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED) {
                return;
            }
            Thread.sleep(100);
        }
        fail("TestRun " + runId + " did not reach COMPLETED within 25 seconds");
    }
}
