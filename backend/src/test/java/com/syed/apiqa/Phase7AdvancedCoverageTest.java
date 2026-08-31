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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase7AdvancedCoverageTest {

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
    private ExecutionRepository executionRepository;

    @Autowired
    private EndpointCoverageRepository endpointCoverageRepository;

    @Autowired
    private CleanupRecordRepository cleanupRecordRepository;

    @BeforeAll
    static void setupWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        // 1. Live OpenAPI 3.0 Contract with Path Parameters, Pagination, Enums, and Numeric Boundaries
        String openApiJson = """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "Production E-Commerce API",
                    "version": "v1.0.0"
                  },
                  "paths": {
                    "/products": {
                      "get": {
                        "summary": "List paginated products with filters",
                        "operationId": "listProducts",
                        "parameters": [
                          {"name": "page", "in": "query", "schema": {"type": "integer", "default": 1, "minimum": 1}},
                          {"name": "pageSize", "in": "query", "schema": {"type": "integer", "default": 10, "maximum": 100}},
                          {"name": "search", "in": "query", "schema": {"type": "string"}},
                          {"name": "sort", "in": "query", "schema": {"type": "string", "enum": ["asc", "desc"]}}
                        ],
                        "responses": {
                          "200": {"description": "List of products", "content": {"application/json": {"schema": {"type": "array"}}}}
                        }
                      },
                      "post": {
                        "summary": "Create a new product",
                        "operationId": "createProduct",
                        "requestBody": {
                          "required": true,
                          "content": {
                            "application/json": {
                              "schema": {
                                "type": "object",
                                "required": ["name", "price", "category"],
                                "properties": {
                                  "name": {"type": "string", "minLength": 2, "maxLength": 100},
                                  "price": {"type": "number", "minimum": 1},
                                  "category": {"type": "string", "enum": ["ELECTRONICS", "BOOKS", "APPAREL"]}
                                }
                              }
                            }
                          }
                        },
                        "responses": {
                          "201": {"description": "Product created", "content": {"application/json": {"schema": {"type": "object"}}}}
                        }
                      }
                    },
                    "/products/{productId}": {
                      "get": {
                        "summary": "Get product by ID",
                        "operationId": "getProductById",
                        "parameters": [
                          {"name": "productId", "in": "path", "required": true, "schema": {"type": "string"}}
                        ],
                        "responses": {
                          "200": {"description": "Product found", "headers": {"ETag": {"schema": {"type": "string"}}}},
                          "304": {"description": "Not modified"}
                        }
                      },
                      "put": {
                        "summary": "Update product",
                        "operationId": "updateProduct",
                        "parameters": [
                          {"name": "productId", "in": "path", "required": true, "schema": {"type": "string"}}
                        ],
                        "requestBody": {
                          "required": true,
                          "content": {"application/json": {"schema": {"type": "object"}}}
                        },
                        "responses": {
                          "200": {"description": "Product updated"}
                        }
                      },
                      "delete": {
                        "summary": "Delete product",
                        "operationId": "deleteProduct",
                        "parameters": [
                          {"name": "productId", "in": "path", "required": true, "schema": {"type": "string"}}
                        ],
                        "responses": {
                          "204": {"description": "Product deleted"},
                          "404": {"description": "Not found"}
                        }
                      }
                    }
                  }
                }
                """;

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(openApiJson)));

        // Positive Creation Stub (requires valid fields)
        wireMockServer.stubFor(post(urlEqualTo("/products"))
                .atPriority(5)
                .withRequestBody(matchingJsonPath("$.name", matching("^[a-zA-Z0-9].*")))
                .withRequestBody(matchingJsonPath("$.price", matching("^[0-9]+(\\.[0-9]+)?$")))
                .withRequestBody(matchingJsonPath("$.category", matching("ELECTRONICS|BOOKS|APPAREL")))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"tag_prod_4001\"")
                        .withBody("{\"id\":\"prod_4001\",\"name\":\"Pro Laptop\",\"price\":1299.99,\"category\":\"ELECTRONICS\"}")));

        // Negative Validation Fallback (any invalid or mutated payload rejected with 422)
        wireMockServer.stubFor(post(urlEqualTo("/products"))
                .atPriority(10)
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Contract validation failed: invalid payload or missing required fields\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/products/prod_4001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"tag_prod_4001\"")
                        .withBody("{\"id\":\"prod_4001\",\"name\":\"Pro Laptop\",\"price\":1299.99,\"category\":\"ELECTRONICS\"}")));

        // ETag Conditional Request: 304 Not Modified
        wireMockServer.stubFor(get(urlEqualTo("/products/prod_4001"))
                .withHeader("If-None-Match", matching(".*tag_prod_4001.*"))
                .willReturn(aResponse()
                        .withStatus(304)
                        .withHeader("ETag", "\"tag_prod_4001\"")));

        wireMockServer.stubFor(put(urlEqualTo("/products/prod_4001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"prod_4001\",\"name\":\"Pro Laptop Max\",\"price\":1499.99,\"category\":\"ELECTRONICS\"}")));

        wireMockServer.stubFor(delete(urlEqualTo("/products/prod_4001"))
                .willReturn(aResponse().withStatus(204)));

        // Pagination & Search Stubs
        wireMockServer.stubFor(get(urlEqualTo("/products?page=1&pageSize=10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"prod_4001\",\"name\":\"Pro Laptop\"}]")));

        wireMockServer.stubFor(get(urlEqualTo("/products?page=2&pageSize=10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        wireMockServer.stubFor(get(urlEqualTo("/products?search=test&sort=desc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"prod_4001\",\"name\":\"Test Item\"}]")));

        wireMockServer.stubFor(get(urlEqualTo("/products?page=0&pageSize=1000"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Invalid page number: minimum is 1\"}")));
    }

    @AfterAll
    static void tearDownWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testPhase7AdvancedCoverageAndIntelligence() throws Exception {
        String specUrl = baseUrl + "/v3/api-docs";

        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        run.setOwnerId("user_phase7_tester");
        testRunRepository.save(run);

        // Execute background test run
        runManager.executeRunAsync(run.getId(), "NONE", null);

        // Await completion
        TestRun completedRun = null;
        long deadline = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < deadline) {
            completedRun = testRunRepository.findById(run.getId()).orElseThrow();
            if (completedRun.getStatus() == RunStatus.COMPLETED || completedRun.getStatus() == RunStatus.FAILED) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(completedRun);
        assertEquals(RunStatus.COMPLETED, completedRun.getStatus(),
                "Phase 7 Run must complete successfully. Error: " + completedRun.getErrorMessage());

        // 1. Verify Path Parameter Resolution
        // The path /products/{productId} must have resolved to /products/prod_4001
        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(run.getId());
        assertFalse(cases.isEmpty(), "Test cases must be generated");

        TestCase crudCase = cases.stream()
                .filter(c -> "CRUD_WORKFLOW".equals(c.getScenarioType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CRUD_WORKFLOW case must be planned"));

        List<TestStep> crudSteps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(crudCase.getId());
        assertTrue(crudSteps.stream().anyMatch(s -> s.getResolvedUrl() != null && s.getResolvedUrl().contains("/products/prod_4001")),
                "Path parameter {productId} must be resolved using captured product ID");

        // 2. Verify Conditional Request & ETag (304 Not Modified)
        TestStep condStep = crudSteps.stream()
                .filter(s -> s.getName().contains("CONDITIONAL READ"))
                .findFirst()
                .orElse(null);
        assertNotNull(condStep, "Conditional ETag step must be planned");
        assertEquals(StepStatus.PASSED, condStep.getStatus(), "Conditional ETag request must pass with 304");

        // 3. Verify Pagination & Filter Scenarios
        TestCase pageCase = cases.stream()
                .filter(c -> "PAGINATION_AND_FILTERING".equals(c.getScenarioType()))
                .findFirst()
                .orElse(null);
        assertNotNull(pageCase, "Pagination and filtering test case must be planned");

        List<TestStep> pageSteps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(pageCase.getId());
        assertTrue(pageSteps.stream().anyMatch(s -> s.getPathTemplate().contains("page=1")), "Page 1 test step required");
        assertTrue(pageSteps.stream().anyMatch(s -> s.getPathTemplate().contains("search=test")), "Filter & Sort test step required");

        // 4. Verify Negative Robustness Variants
        TestCase negCase = cases.stream()
                .filter(c -> "NEGATIVE_ROBUSTNESS".equals(c.getScenarioType()))
                .findFirst()
                .orElse(null);
        assertNotNull(negCase, "Negative robustness test case must be planned");

        List<TestStep> negSteps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(negCase.getId());
        assertTrue(negSteps.size() >= 3, "At least 3 negative variants should be planned");
        assertTrue(negSteps.stream().anyMatch(s -> s.getStatus() == StepStatus.PASSED),
                "Negative steps receiving 400/422 must pass validation verification");

        // 5. Verify Deterministic API QA Coverage & Endpoint Classification
        assertNotNull(completedRun.getCoverageScore(), "API QA Coverage score must be calculated");
        assertTrue(completedRun.getCoverageScore() > 0.0, "Coverage score must be positive");
        assertNotNull(completedRun.getCoverageSummaryJson(), "Coverage summary JSON must be persisted");

        List<EndpointCoverage> coverages = endpointCoverageRepository.findByTestRunIdOrderByPathAsc(run.getId());
        assertFalse(coverages.isEmpty(), "Endpoint coverage records must be persisted");
        assertTrue(coverages.stream().anyMatch(ec -> ec.getClassification() == EndpointCoverage.Classification.FULL),
                "At least one endpoint must achieve FULL coverage classification");

        // 6. Verify Cleanup Endpoint Selection: Discovered DELETE /products/{productId} was recorded and executed
        List<CleanupRecord> cleanups = cleanupRecordRepository.findByTestRunId(run.getId());
        assertFalse(cleanups.isEmpty(), "Cleanup records must be registered for created products");
        assertTrue(cleanups.stream().allMatch(c -> c.getDeleteEndpoint().contains("/products")),
                "Discovered DELETE endpoint must be selected for teardown");
        assertEquals("EXECUTED", completedRun.getCleanupStatus(), "Automated cleanup must execute in STAGING mode");
    }
}
