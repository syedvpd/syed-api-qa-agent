package com.syed.apiqa.execution;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.syed.apiqa.domain.EnvironmentType;
import com.syed.apiqa.domain.StepStatus;
import com.syed.apiqa.domain.TestCase;
import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.domain.TestStep;
import com.syed.apiqa.persistence.TestCaseRepository;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.persistence.TestStepRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ProductionSafetyExecutionTest {

    private static WireMockServer wireMockServer;
    private static String baseUrl;

    @Autowired
    private HttpExecutionEngine httpExecutionEngine;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestStepRepository testStepRepository;

    @BeforeAll
    static void setUpServer() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        // 429 Rate limited endpoint with Retry-After
        wireMockServer.stubFor(get(urlEqualTo("/rate-limited"))
                .inScenario("RateLimit")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "1")
                        .withBody("{\"error\":\"rate_limit_exceeded\"}"))
                .willSetStateTo("Retried"));

        wireMockServer.stubFor(get(urlEqualTo("/rate-limited"))
                .inScenario("RateLimit")
                .whenScenarioStateIs("Retried")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success_after_rate_limit\"}")));

        // Big body endpoint > 2MB
        StringBuilder sb = new StringBuilder(2_500_000);
        for (int i = 0; i < 250_000; i++) {
            sb.append("0123456789");
        }
        wireMockServer.stubFor(get(urlEqualTo("/big-payload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(sb.toString())));
    }

    @AfterAll
    static void tearDownServer() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private record StepWithRun(TestStep step, String runId) {}

    private StepWithRun createAndPersistStep(String method, String pathTemplate, int expectedStatus) {
        TestRun run = new TestRun(UUID.randomUUID().toString(), baseUrl + "/v3/api-docs", EnvironmentType.STAGING);
        testRunRepository.save(run);

        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName("Test Case for " + method);
        tc.setScenarioType("CONTRACT_CHECK");
        tc.setCategory("POSITIVE_CRUD");
        testCaseRepository.save(tc);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(tc);
        step.setName("Step " + method);
        step.setMethod(method);
        step.setPathTemplate(pathTemplate);
        step.setExpectedStatus(expectedStatus);
        TestStep savedStep = testStepRepository.save(step);
        return new StepWithRun(savedStep, run.getId());
    }

    @Test
    void destructiveDeleteMustBeSkippedInProductionMode() {
        StepWithRun pair = createAndPersistStep("DELETE", "/resource/123", 204);
        ExecutionContext context = new ExecutionContext(pair.runId());

        HttpExecutionEngine.StepExecutionOutcome outcome = httpExecutionEngine.executeStep(
                pair.step(), baseUrl, context, EnvironmentType.PRODUCTION, "NONE", null
        );

        assertEquals(StepStatus.SKIPPED, outcome.getFinalStatus());
        assertTrue(outcome.getFailureMessage().contains("HTTP DELETE is disabled by default in PRODUCTION mode"));
    }

    @Test
    void rateLimit429WithRetryAfterIsHandledSafely() {
        wireMockServer.resetScenarios();
        StepWithRun pair = createAndPersistStep("GET", "/rate-limited", 200);
        ExecutionContext context = new ExecutionContext(pair.runId());

        HttpExecutionEngine.StepExecutionOutcome outcome = httpExecutionEngine.executeStep(
                pair.step(), baseUrl, context, EnvironmentType.STAGING, "NONE", null
        );

        assertEquals(StepStatus.PASSED, outcome.getFinalStatus(), "Engine should retry bounded GET after 429 Retry-After");
        assertNotNull(outcome.getExecution());
        assertEquals(200, outcome.getExecution().getResponseStatus());
    }

    @Test
    void largeResponseBodyMustBeSafelyTruncated() {
        StepWithRun pair = createAndPersistStep("GET", "/big-payload", 200);
        ExecutionContext context = new ExecutionContext(pair.runId());

        HttpExecutionEngine.StepExecutionOutcome outcome = httpExecutionEngine.executeStep(
                pair.step(), baseUrl, context, EnvironmentType.STAGING, "NONE", null
        );

        assertNotNull(outcome.getExecution());
        String body = outcome.getExecution().getResponseBody();
        assertNotNull(body);
        assertTrue(body.contains("[RESPONSE TRUNCATED - EXCEEDED 2MB LIMIT]"), "Response over 2MB must be truncated");
        assertTrue(body.length() <= 2_100_000, "Truncated body must not exceed buffer bounds");
    }
}
