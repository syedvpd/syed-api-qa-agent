package com.syed.apiqa;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.run.RunManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase1FailureAndEdgeCasesTest {

    private static WireMockServer wireMockServer;
    private static String baseUrl;

    @Autowired
    private RunManager runManager;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestStepRepository testStepRepository;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        String openApiJson = "{\n" +
                "  \"openapi\": \"3.0.1\",\n" +
                "  \"info\": { \"title\": \"Failure Mode API\", \"version\": \"1.0.0\" },\n" +
                "  \"servers\": [{ \"url\": \"" + baseUrl + "\" }],\n" +
                "  \"paths\": {\n" +
                "    \"/items\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"Create Item\",\n" +
                "        \"responses\": { \"201\": { \"description\": \"Created\" } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/items/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Get Item\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/health\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Health Check\",\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(openApiJson)));

        // Items POST returns 500 Internal Server Error
        wireMockServer.stubFor(post(urlEqualTo("/items"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Database connection failure\"}")));

        // Independent Health endpoint returns 200 OK
        wireMockServer.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"HEALTHY\"}")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testFailureIsolationAndIndependentBranchContinuation() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        testRunRepository.save(run);

        runManager.executeRunAsync(run.getId(), "NONE", null);

        // Wait for background execution
        TestRun completedRun = null;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            completedRun = testRunRepository.findById(run.getId()).orElseThrow();
            if (completedRun.getStatus() == RunStatus.COMPLETED || completedRun.getStatus() == RunStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(completedRun);
        assertEquals(RunStatus.COMPLETED, completedRun.getStatus(), "Run error if any: " + completedRun.getErrorMessage());

        // 1. One test failed (POST /items returned 500)
        assertEquals(1, completedRun.getFailedTests());

        // 2. Dependent step (GET /items/{id}) was BLOCKED
        assertTrue(completedRun.getBlockedTests() >= 1, "Dependent steps must be BLOCKED");

        // 3. Independent test (GET /health) was NOT stopped and PASSED!
        assertTrue(completedRun.getPassedTests() >= 1, "Independent endpoints must continue and pass");

        // Verify step statuses in database
        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(run.getId());
        for (TestCase tc : cases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            if (tc.getName().contains("Item")) {
                assertEquals(StepStatus.FAILED, steps.get(0).getStatus()); // POST /items
                assertEquals(StepStatus.BLOCKED, steps.get(1).getStatus()); // GET /items/{id}
                assertTrue(steps.get(1).getFailureReason().contains("BLOCKED"));
            } else if (tc.getName().contains("health")) {
                assertEquals(StepStatus.PASSED, steps.get(0).getStatus()); // GET /health
            }
        }
    }
}
