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
public class Phase1PipelineIntegrationTest {

    private static WireMockServer wireMockServer;
    private static String baseUrl;

    @Autowired
    private RunManager runManager;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestStepRepository testStepRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ReportRepository reportRepository;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        // 1. Stub OpenAPI 3 Specification endpoint
        String openApiJson = "{\n" +
                "  \"openapi\": \"3.0.1\",\n" +
                "  \"info\": { \"title\": \"Mock Deployed API\", \"version\": \"1.0.0\" },\n" +
                "  \"servers\": [{ \"url\": \"" + baseUrl + "\" }],\n" +
                "  \"paths\": {\n" +
                "    \"/users\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"Create User\",\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\n" +
                "                \"type\": \"object\",\n" +
                "                \"required\": [\"name\", \"email\"],\n" +
                "                \"properties\": {\n" +
                "                  \"name\": { \"type\": \"string\" },\n" +
                "                  \"email\": { \"type\": \"string\", \"format\": \"email\" }\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"responses\": { \"201\": { \"description\": \"Created\" } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/users/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Get User by ID\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" }, \"404\": { \"description\": \"Not Found\" } }\n" +
                "      },\n" +
                "      \"patch\": {\n" +
                "        \"summary\": \"Update User\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      },\n" +
                "      \"delete\": {\n" +
                "        \"summary\": \"Delete User\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"204\": { \"description\": \"Deleted\" } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/products\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"List Products\",\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(openApiJson)));

        // 2. Stub live backend endpoints
        wireMockServer.stubFor(post(urlEqualTo("/users"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.name", matching("^[a-zA-Z0-9_ ]+$")))
                .withRequestBody(matchingJsonPath("$.email", matching("^[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+$")))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"usr_999\",\"name\":\"Syed QA\",\"email\":\"syed@test.com\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/users"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Validation failed: missing required fields\"}")));

        // Scenarios for stateful CRUD flow
        wireMockServer.stubFor(get(urlEqualTo("/users/usr_999"))
                .inScenario("UserCrud")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"usr_999\",\"name\":\"Syed QA\",\"email\":\"syed@test.com\"}")));

        wireMockServer.stubFor(patch(urlEqualTo("/users/usr_999"))
                .inScenario("UserCrud")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"usr_999\",\"name\":\"Syed Updated\",\"email\":\"syed@test.com\"}")));

        wireMockServer.stubFor(delete(urlEqualTo("/users/usr_999"))
                .inScenario("UserCrud")
                .willSetStateTo("USER_DELETED")
                .willReturn(aResponse().withStatus(204)));

        wireMockServer.stubFor(get(urlEqualTo("/users/usr_999"))
                .inScenario("UserCrud")
                .whenScenarioStateIs("USER_DELETED")
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"User not found\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/products"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"p1\",\"name\":\"Widget\"}]")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testCompletePhase1PipelineEndToEnd() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        testRunRepository.save(run);

        // Execute run
        runManager.executeRunAsync(run.getId(), "NONE", null);

        // Wait for asynchronous background execution to complete
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
        assertTrue(completedRun.getDurationMs() > 0, "Execution duration should be recorded");
        assertTrue(completedRun.getTotalEndpoints() >= 3, "Should discover at least 3 endpoints");

        // 2. Verify API Inventory was persisted
        List<ApiEndpoint> endpoints = apiEndpointRepository.findByTestRunId(run.getId());
        assertFalse(endpoints.isEmpty(), "Endpoints must be persisted in database");
        assertTrue(endpoints.stream().anyMatch(e -> "POST".equals(e.getMethod()) && "/users".equals(e.getPath())));
        assertTrue(endpoints.stream().anyMatch(e -> "GET".equals(e.getMethod()) && "/products".equals(e.getPath())));

        // 3. Verify Test Cases and Steps planned
        List<TestCase> testCases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(run.getId());
        assertFalse(testCases.isEmpty(), "Test cases must be formulated");

        // 4. Verify Variable Propagation and CRUD execution
        List<Execution> executions = executionRepository.findAll();
        assertFalse(executions.isEmpty(), "Execution records must be persisted");

        // Verify all CRUD workflow steps passed
        assertTrue(completedRun.getPassedTests() > 0, "At least one test should pass");
        assertEquals(0, completedRun.getFailedTests(), "No tests should fail in happy path");
        assertEquals(0, completedRun.getBlockedTests(), "No tests should be blocked in happy path");

        // 5. Verify HTML Report was generated and persisted
        Report report = reportRepository.findByTestRunId(run.getId()).orElseThrow();
        assertNotNull(report.getHtmlContent());
        assertTrue(report.getHtmlContent().contains("Syed API QA Agent &bull; Audit Report"));
        assertTrue(report.getHtmlContent().contains("usr_999"));
    }
}
