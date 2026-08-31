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
public class Phase2AdvancedPipelineTest {

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

    @Autowired
    private CleanupRecordRepository cleanupRecordRepository;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        // 1. OpenAPI Specification with validation schemas (required, types, boundaries, enums)
        String openApiJson = "{\n" +
                "  \"openapi\": \"3.0.1\",\n" +
                "  \"info\": { \"title\": \"Phase 2 Advanced Target API\", \"version\": \"2.0.0\" },\n" +
                "  \"servers\": [{ \"url\": \"" + baseUrl + "\" }],\n" +
                "  \"paths\": {\n" +
                "    \"/accounts\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"Create Account\",\n" +
                "        \"requestBody\": {\n" +
                "          \"content\": {\n" +
                "            \"application/json\": {\n" +
                "              \"schema\": {\n" +
                "                \"type\": \"object\",\n" +
                "                \"required\": [\"username\", \"email\", \"age\", \"tier\"],\n" +
                "                \"properties\": {\n" +
                "                  \"username\": { \"type\": \"string\", \"maxLength\": 20 },\n" +
                "                  \"email\": { \"type\": \"string\", \"format\": \"email\" },\n" +
                "                  \"age\": { \"type\": \"integer\", \"minimum\": 18, \"maximum\": 120 },\n" +
                "                  \"tier\": { \"type\": \"string\", \"enum\": [\"BASIC\", \"PRO\", \"ENTERPRISE\"] }\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        },\n" +
                "        \"responses\": {\n" +
                "          \"201\": { \"description\": \"Created\" },\n" +
                "          \"400\": { \"description\": \"Bad Request\" },\n" +
                "          \"422\": { \"description\": \"Unprocessable Entity\" }\n" +
                "        }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/accounts/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Get Account\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" }, \"404\": { \"description\": \"Not Found\" } }\n" +
                "      },\n" +
                "      \"delete\": {\n" +
                "        \"summary\": \"Delete Account\",\n" +
                "        \"parameters\": [{ \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"string\" } }],\n" +
                "        \"responses\": { \"204\": { \"description\": \"Deleted\" } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/secure-data\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"Protected Route\",\n" +
                "        \"responses\": {\n" +
                "          \"200\": { \"description\": \"OK\" },\n" +
                "          \"401\": { \"description\": \"Unauthorized\" }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(openApiJson)));

        // 2. Dynamic Auth endpoints
        wireMockServer.stubFor(post(urlEqualTo("/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"initial_token_123\",\"token_type\":\"Bearer\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/auth/refresh"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"refreshed_token_999\",\"token_type\":\"Bearer\"}")));

        // 3. Positive Creation (Priority 1)
        wireMockServer.stubFor(post(urlEqualTo("/accounts"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.username"))
                .withRequestBody(matchingJsonPath("$.email", matching(".+@.+\\..+")))
                .withRequestBody(matchingJsonPath("$.age", matching("^(1[89]|[2-9][0-9]|1[01][0-9]|120)$")))
                .withRequestBody(matchingJsonPath("$.tier", matching("BASIC|PRO|ENTERPRISE")))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"acc_555\",\"username\":\"syed_user\",\"email\":\"syed@apiqa.org\",\"age\":25,\"tier\":\"PRO\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/accounts/acc_555"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"acc_555\",\"username\":\"syed_user\"}")));

        wireMockServer.stubFor(delete(urlEqualTo("/accounts/acc_555"))
                .willReturn(aResponse().withStatus(204)));

        // 4. Negative Fuzzing Variants against /accounts (Priority 5 fallback)
        wireMockServer.stubFor(post(urlEqualTo("/accounts"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Validation rejection: invalid or boundary payload attributes\"}")));

        // 5. Auth expiration flow: initial token returns 401, refreshed token returns 200
        wireMockServer.stubFor(get(urlEqualTo("/secure-data"))
                .withHeader("Authorization", equalTo("Bearer initial_token_123"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"token_expired\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/secure-data"))
                .withHeader("Authorization", equalTo("Bearer refreshed_token_999"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":\"confidential_vault_content\"}")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testPhase2AdvancedPipelineComplete() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        run.setAuthLoginUrl(baseUrl + "/auth/login");
        run.setAuthLoginPayload("{\"username\":\"qa_agent\",\"password\":\"secret_pass\"}");
        run.setAuthTokenPath("access_token");
        run.setAuthRefreshUrl(baseUrl + "/auth/refresh");
        testRunRepository.save(run);

        // Execute run
        runManager.executeRunAsync(run.getId(), "BEARER", null);

        // Wait for asynchronous background execution to complete
        TestRun completedRun = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            completedRun = testRunRepository.findById(run.getId()).orElseThrow();
            if (completedRun.getStatus() == RunStatus.COMPLETED || completedRun.getStatus() == RunStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(completedRun);
        assertEquals(RunStatus.COMPLETED, completedRun.getStatus(), "Run error: " + completedRun.getErrorMessage());

        // 1. Verify Negative Robustness Scenarios Formulated and Executed
        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(run.getId());
        assertTrue(cases.stream().anyMatch(c -> "NEGATIVE_VALIDATION".equals(c.getCategory())),
                "Negative robustness test case must be formulated");

        TestCase negCase = cases.stream()
                .filter(c -> "NEGATIVE_VALIDATION".equals(c.getCategory()))
                .findFirst()
                .orElseThrow();
        List<TestStep> negSteps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(negCase.getId());
        assertFalse(negSteps.isEmpty(), "Negative steps must be generated");

        // Verify that negative steps received 400/422 and were correctly marked PASSED
        boolean anyNegativePassed = negSteps.stream().anyMatch(s -> s.getStatus() == StepStatus.PASSED);
        assertTrue(anyNegativePassed, "Negative validation steps should pass when server rejects invalid input with 400/422");

        // 2. Verify Dynamic Auth Token Injection & 401 Refresh
        // The secure-data step should have initially received 401, refreshed token, and PASSED with 200
        wireMockServer.verify(postRequestedFor(urlEqualTo("/auth/login")));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/auth/refresh")));
        wireMockServer.verify(getRequestedFor(urlEqualTo("/secure-data"))
                .withHeader("Authorization", equalTo("Bearer refreshed_token_999")));

        // 3. Verify Automated Reverse-Topological Cleanup Execution
        List<CleanupRecord> cleanupRecords = cleanupRecordRepository.findByTestRunId(run.getId());
        assertFalse(cleanupRecords.isEmpty(), "Created resources must be registered for cleanup");
        assertEquals("EXECUTED", completedRun.getCleanupStatus(), "Cleanup stage must execute");
        assertTrue(cleanupRecords.stream().allMatch(r -> "COMPLETED".equals(r.getStatus())),
                "All cleanup records must reach COMPLETED status in non-production mode");

        // Verify WireMock actually received the DELETE call for the created resource
        wireMockServer.verify(deleteRequestedFor(urlEqualTo("/accounts/acc_555")));
    }

    @Test
    void testProductionModeSuppressesDestructiveCleanup() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.PRODUCTION);
        testRunRepository.save(run);

        // Pre-register a cleanup record
        CleanupRecord record = new CleanupRecord(
                UUID.randomUUID().toString(),
                run,
                "account",
                "acc_prod_999",
                "/accounts/{id}",
                1
        );
        cleanupRecordRepository.save(record);

        runManager.executeRunAsync(run.getId(), "NONE", null);

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
        assertEquals("SKIPPED", completedRun.getCleanupStatus(), "Cleanup must be SKIPPED in PRODUCTION mode");

        CleanupRecord updatedRecord = cleanupRecordRepository.findById(record.getId()).orElseThrow();
        assertEquals("SKIPPED", updatedRecord.getStatus(), "CleanupRecord must be marked SKIPPED in PRODUCTION mode");
        assertTrue(updatedRecord.getErrorMessage().contains("PRODUCTION"), "Reason must mention PRODUCTION mode");
    }
}
