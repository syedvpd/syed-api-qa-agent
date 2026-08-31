package com.syed.apiqa.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.generation.DeterministicDataGenerator;
import com.syed.apiqa.generation.NegativeDataGenerator;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Plans and formulates executable TestCases and TestSteps for a TestRun.
 * Builds:
 * 1. Coordinated positive CRUD workflows where parent-child operations exist.
 * 2. Negative & boundary fuzzing suites for payload validation robustness.
 * 3. Single-endpoint contract verification for independent routes.
 */
@Service
public class TestPlanService {

    private final DeterministicDataGenerator dataGenerator;
    private final NegativeDataGenerator negativeGenerator;
    private final DependencyEngine dependencyEngine;
    private final ObjectMapper objectMapper;

    public TestPlanService(DeterministicDataGenerator dataGenerator,
                           NegativeDataGenerator negativeGenerator,
                           DependencyEngine dependencyEngine,
                           ObjectMapper objectMapper) {
        this.dataGenerator = dataGenerator;
        this.negativeGenerator = negativeGenerator;
        this.dependencyEngine = dependencyEngine;
        this.objectMapper = objectMapper;
    }

    public static class PlanResult {
        private final List<TestCase> testCases;
        private final Map<String, List<TestStep>> stepsByCaseId;

        public PlanResult(List<TestCase> testCases, Map<String, List<TestStep>> stepsByCaseId) {
            this.testCases = testCases;
            this.stepsByCaseId = stepsByCaseId;
        }

        public List<TestCase> getTestCases() { return testCases; }
        public Map<String, List<TestStep>> getStepsByCaseId() { return stepsByCaseId; }
    }

    public PlanResult buildTestPlan(TestRun testRun, List<ApiEndpoint> endpoints, List<Dependency> dependencies) {
        List<TestCase> testCases = new ArrayList<>();
        Map<String, List<TestStep>> stepsByCaseId = new HashMap<>();

        // 7.11 Large API Support: Bounded processing limits
        final int MAX_ENDPOINTS = 500;
        final int MAX_NEGATIVE_PER_EP = 5;
        List<ApiEndpoint> boundedEndpoints = (endpoints != null && endpoints.size() > MAX_ENDPOINTS)
                ? endpoints.subList(0, MAX_ENDPOINTS)
                : (endpoints != null ? endpoints : Collections.emptyList());

        // Group endpoints by entity name
        Map<String, List<ApiEndpoint>> entityMap = new LinkedHashMap<>();
        for (ApiEndpoint ep : boundedEndpoints) {
            String entity = dependencyEngine.extractEntityNameFromPath(ep.getPath());
            entityMap.computeIfAbsent(entity, k -> new ArrayList<>()).add(ep);
        }

        int caseOrder = 1;
        Set<String> plannedEndpointIds = new HashSet<>();

        // 1. Plan CRUD workflows for entities that support POST and parameterized child routes
        for (Map.Entry<String, List<ApiEndpoint>> entry : entityMap.entrySet()) {
            String entity = entry.getKey();
            List<ApiEndpoint> entityEndpoints = entry.getValue();

            ApiEndpoint postEp = findEndpoint(entityEndpoints, "POST", false);
            ApiEndpoint getByIdEp = findEndpoint(entityEndpoints, "GET", true);
            ApiEndpoint updateEp = findEndpoint(entityEndpoints, "PATCH", true);
            if (updateEp == null) {
                updateEp = findEndpoint(entityEndpoints, "PUT", true);
            }
            ApiEndpoint deleteEp = findEndpoint(entityEndpoints, "DELETE", true);

            if (postEp != null && getByIdEp != null) {
                // We have a verifiable stateful CRUD lifecycle
                TestCase crudCase = new TestCase();
                crudCase.setId(UUID.randomUUID().toString());
                crudCase.setTestRun(testRun);
                crudCase.setName("Lifecycle CRUD: " + capitalize(entity));
                crudCase.setDescription("Sequential stateful CRUD verification: CREATE -> READ -> UPDATE -> READ -> DELETE -> VERIFY 404");
                crudCase.setScenarioType("CRUD_WORKFLOW");
                crudCase.setCategory("POSITIVE_CRUD");
                crudCase.setExecutionOrder(caseOrder++);
                crudCase.setStatus(StepStatus.PENDING);
                crudCase.setCreatedAt(OffsetDateTime.now());

                List<TestStep> steps = new ArrayList<>();
                int stepOrder = 1;

                // Step 1: POST /entity (CREATE)
                TestStep createStep = createStep(crudCase, postEp, stepOrder++, "CREATE " + entity, "POST", postEp.getPath(), 201, testRun.getId());
                steps.add(createStep);
                plannedEndpointIds.add(postEp.getId());

                // Step 2: GET /entity/{id} (READ after create)
                String getPath = replacePathParamWithVar(getByIdEp.getPath(), "{{" + entity + ".id}}");
                TestStep readStep1 = createStep(crudCase, getByIdEp, stepOrder++, "READ " + entity + " by ID", "GET", getPath, 200, testRun.getId());
                steps.add(readStep1);

                // Step 2b: Conditional ETag GET /entity/{id} only if contract specifies ETag or 304
                if (getByIdEp.getResponseSchemas() != null &&
                        (getByIdEp.getResponseSchemas().toLowerCase().contains("etag") ||
                         getByIdEp.getResponseSchemas().contains("304"))) {
                    TestStep condStep = createStep(crudCase, getByIdEp, stepOrder++, "CONDITIONAL READ " + entity + " (If-None-Match)", "GET", getPath, 304, testRun.getId());
                    condStep.setRequestHeaders("If-None-Match: {{" + entity + ".etag}}");
                    steps.add(condStep);
                }

                // Step 3: PATCH/PUT /entity/{id} (UPDATE)
                if (updateEp != null) {
                    String updatePath = replacePathParamWithVar(updateEp.getPath(), "{{" + entity + ".id}}");
                    TestStep updateStep = createStep(crudCase, updateEp, stepOrder++, "UPDATE " + entity, updateEp.getMethod(), updatePath, 200, testRun.getId());
                    steps.add(updateStep);
                    plannedEndpointIds.add(updateEp.getId());

                    // Step 4: GET /entity/{id} (READ after update)
                    TestStep readStep2 = createStep(crudCase, getByIdEp, stepOrder++, "VERIFY UPDATE " + entity, "GET", getPath, 200, testRun.getId());
                    steps.add(readStep2);
                }

                // Step 5: DELETE /entity/{id} (DELETE)
                if (deleteEp != null) {
                    String deletePath = replacePathParamWithVar(deleteEp.getPath(), "{{" + entity + ".id}}");
                    TestStep deleteStep = createStep(crudCase, deleteEp, stepOrder++, "DELETE " + entity, "DELETE", deletePath, 204, testRun.getId());
                    steps.add(deleteStep);
                    plannedEndpointIds.add(deleteEp.getId());

                    // Step 6: GET /entity/{id} (VERIFY 404 NOT FOUND)
                    TestStep verify404Step = createStep(crudCase, getByIdEp, stepOrder++, "VERIFY 404 AFTER DELETE " + entity, "GET", getPath, 404, testRun.getId());
                    steps.add(verify404Step);
                }

                plannedEndpointIds.add(getByIdEp.getId());

                testCases.add(crudCase);
                stepsByCaseId.put(crudCase.getId(), steps);
            }
        }

        // 2. Plan Pagination & Filter Tests for Collection Endpoints with declared pagination params
        for (ApiEndpoint ep : boundedEndpoints) {
            if ("GET".equalsIgnoreCase(ep.getMethod()) && !ep.getPath().contains("{")
                    && ep.getParameters() != null
                    && (ep.getParameters().toLowerCase().contains("page") || ep.getParameters().toLowerCase().contains("limit") || ep.getParameters().toLowerCase().contains("offset"))) {
                TestCase pageCase = new TestCase();
                pageCase.setId(UUID.randomUUID().toString());
                pageCase.setTestRun(testRun);
                pageCase.setName("Pagination & Filter: " + ep.getPath());
                pageCase.setDescription("Deterministic query pagination, boundary limits, and filter verification");
                pageCase.setScenarioType("PAGINATION_AND_FILTERING");
                pageCase.setCategory("PAGINATION_FILTER");
                pageCase.setExecutionOrder(caseOrder++);
                pageCase.setStatus(StepStatus.PENDING);
                pageCase.setCreatedAt(OffsetDateTime.now());

                List<TestStep> pageSteps = new ArrayList<>();
                int pOrder = 1;

                // Page 1
                TestStep p1 = createStep(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Page 1)", "GET", ep.getPath() + "?page=1&pageSize=10", 200, testRun.getId());
                pageSteps.add(p1);

                // Page 2
                TestStep p2 = createStep(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Page 2)", "GET", ep.getPath() + "?page=2&pageSize=10", 200, testRun.getId());
                pageSteps.add(p2);

                // Filter & Sort
                TestStep p3 = createStep(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Filter & Sort)", "GET", ep.getPath() + "?search=test&sort=desc", 200, testRun.getId());
                pageSteps.add(p3);

                // Boundary / Malformed page
                TestStep p4 = createStep(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Boundary Page)", "GET", ep.getPath() + "?page=0&pageSize=1000", 200, testRun.getId());
                pageSteps.add(p4);

                testCases.add(pageCase);
                stepsByCaseId.put(pageCase.getId(), pageSteps);
                plannedEndpointIds.add(ep.getId());
            }
        }

        // 3. Plan Negative & Boundary Fuzzing Scenarios for endpoints with request bodies
        for (ApiEndpoint ep : boundedEndpoints) {
            if (("POST".equalsIgnoreCase(ep.getMethod()) || "PUT".equalsIgnoreCase(ep.getMethod()) || "PATCH".equalsIgnoreCase(ep.getMethod()))
                    && ep.getRequestBodySchema() != null) {

                try {
                    Schema<?> schema = objectMapper.readValue(ep.getRequestBodySchema(), Schema.class);
                    String validBody = dataGenerator.generateJsonString(schema, testRun.getId());
                    List<NegativeDataGenerator.NegativePayload> negativeVariants = negativeGenerator.generateNegativeVariants(validBody, ep.getRequestBodySchema());
                    if (negativeVariants.size() > MAX_NEGATIVE_PER_EP) {
                        negativeVariants = negativeVariants.subList(0, MAX_NEGATIVE_PER_EP);
                    }

                    if (!negativeVariants.isEmpty()) {
                        TestCase negCase = new TestCase();
                        negCase.setId(UUID.randomUUID().toString());
                        negCase.setTestRun(testRun);
                        negCase.setName("Negative Robustness: " + ep.getMethod() + " " + ep.getPath());
                        negCase.setDescription("Boundary fuzzing, missing required fields, invalid types, and malformed inputs");
                        negCase.setScenarioType("NEGATIVE_ROBUSTNESS");
                        negCase.setCategory("NEGATIVE_VALIDATION");
                        negCase.setExecutionOrder(caseOrder++);
                        negCase.setStatus(StepStatus.PENDING);
                        negCase.setCreatedAt(OffsetDateTime.now());

                        List<TestStep> negSteps = new ArrayList<>();
                        int negStepOrder = 1;

                        for (NegativeDataGenerator.NegativePayload variant : negativeVariants) {
                            TestStep step = new TestStep();
                            step.setId(UUID.randomUUID().toString());
                            step.setTestCase(negCase);
                            step.setApiEndpoint(ep);
                            step.setStepOrder(negStepOrder++);
                            step.setName(variant.getScenarioName());
                            step.setMethod(ep.getMethod());
                            step.setPathTemplate(ep.getPath());
                            step.setRequestBody(variant.getPayloadJson());
                            step.setExpectedStatus(variant.getExpectedNegativeStatus());
                            step.setStatus(StepStatus.PENDING);
                            negSteps.add(step);
                        }

                        testCases.add(negCase);
                        stepsByCaseId.put(negCase.getId(), negSteps);
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. Plan Single-Endpoint checks for any remaining unexercised endpoints
        for (ApiEndpoint ep : boundedEndpoints) {
            if (!plannedEndpointIds.contains(ep.getId()) && !ep.getPath().contains("{")) {
                TestCase singleCase = new TestCase();
                singleCase.setId(UUID.randomUUID().toString());
                singleCase.setTestRun(testRun);
                singleCase.setName("Endpoint Sanity: " + ep.getMethod() + " " + ep.getPath());
                singleCase.setDescription("Standalone contract verification for " + ep.getMethod() + " " + ep.getPath());
                singleCase.setScenarioType("SINGLE_ENDPOINT");
                singleCase.setCategory("POSITIVE_CRUD");
                singleCase.setExecutionOrder(caseOrder++);
                singleCase.setStatus(StepStatus.PENDING);
                singleCase.setCreatedAt(OffsetDateTime.now());

                TestStep step = createStep(singleCase, ep, 1, ep.getMethod() + " " + ep.getPath(), ep.getMethod(), ep.getPath(), 200, testRun.getId());
                testCases.add(singleCase);
                stepsByCaseId.put(singleCase.getId(), Collections.singletonList(step));
            }
        }

        return new PlanResult(testCases, stepsByCaseId);
    }

    private TestStep createStep(TestCase testCase, ApiEndpoint endpoint, int order, String name,
                                String method, String pathTemplate, int expectedStatus, String runId) {
        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(testCase);
        step.setApiEndpoint(endpoint);
        step.setStepOrder(order);
        step.setName(name);
        step.setMethod(method);
        step.setPathTemplate(pathTemplate);
        step.setExpectedStatus(expectedStatus);
        step.setStatus(StepStatus.PENDING);

        // Generate synthetic body if method is POST, PUT, or PATCH and requestBodySchema is present
        if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))
                && endpoint.getRequestBodySchema() != null) {
            try {
                Schema<?> schema = objectMapper.readValue(endpoint.getRequestBodySchema(), Schema.class);
                String bodyJson = dataGenerator.generateJsonString(schema, runId);
                step.setRequestBody(bodyJson);
            } catch (Exception e) {
                step.setRequestBody("{}");
            }
        }

        return step;
    }

    private ApiEndpoint findEndpoint(List<ApiEndpoint> endpoints, String method, boolean hasPathParam) {
        for (ApiEndpoint ep : endpoints) {
            if (ep.getMethod().equalsIgnoreCase(method)) {
                boolean containsParam = ep.getPath().contains("{");
                if (containsParam == hasPathParam) {
                    return ep;
                }
            }
        }
        return null;
    }

    private String replacePathParamWithVar(String path, String varName) {
        return path.replaceAll("\\{[^}]+\\}", varName);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
