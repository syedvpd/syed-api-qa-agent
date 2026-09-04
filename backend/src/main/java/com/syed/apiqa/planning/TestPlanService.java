package com.syed.apiqa.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.generation.DeterministicDataGenerator;
import com.syed.apiqa.generation.NegativeDataGenerator;
import io.swagger.v3.oas.models.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans and formulates executable TestCases and TestSteps for a TestRun.
 * Builds:
 * 1. Coordinated positive CRUD workflows where parent-child operations exist.
 * 2. Real OpenAPI parameter-driven execution for all endpoints (query, header, path, cookie, body).
 * 3. Contract-derived response status code expectations.
 * 4. Topological execution ordering preserving dependencies.
 * 5. Negative & boundary fuzzing suites for payload validation robustness.
 */
@Service
public class TestPlanService {

    private static final Logger log = LoggerFactory.getLogger(TestPlanService.class);

    private final DeterministicDataGenerator dataGenerator;
    private final NegativeDataGenerator negativeGenerator;
    private final DependencyEngine dependencyEngine;
    private final ObjectMapper objectMapper;
    private final com.syed.apiqa.discovery.OpenApiSchemaRegistry openApiSchemaRegistry;

    public TestPlanService(DeterministicDataGenerator dataGenerator,
                           NegativeDataGenerator negativeGenerator,
                           DependencyEngine dependencyEngine,
                           ObjectMapper objectMapper) {
        this(dataGenerator, negativeGenerator, dependencyEngine, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TestPlanService(DeterministicDataGenerator dataGenerator,
                           NegativeDataGenerator negativeGenerator,
                           DependencyEngine dependencyEngine,
                           ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.syed.apiqa.discovery.OpenApiSchemaRegistry openApiSchemaRegistry) {
        this.dataGenerator = dataGenerator;
        this.negativeGenerator = negativeGenerator;
        this.dependencyEngine = dependencyEngine;
        this.objectMapper = objectMapper;
        this.openApiSchemaRegistry = openApiSchemaRegistry;
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
        return buildTestPlan(testRun, endpoints, dependencies, null);
    }

    public PlanResult buildTestPlan(TestRun testRun, List<ApiEndpoint> endpoints, List<Dependency> dependencies, Map<String, Schema> openApiSchemas) {
        if ((openApiSchemas == null || openApiSchemas.isEmpty()) && openApiSchemaRegistry != null && testRun != null) {
            openApiSchemas = openApiSchemaRegistry.getSchemas(testRun.getId());
        }
        List<TestCase> testCases = new ArrayList<>();
        Map<String, List<TestStep>> stepsByCaseId = new HashMap<>();

        if (endpoints == null || endpoints.isEmpty()) {
            return new PlanResult(testCases, stepsByCaseId);
        }

        final int MAX_NEGATIVE_PER_EP = 5;

        // Group endpoints by entity name
        Map<String, List<ApiEndpoint>> entityMap = new LinkedHashMap<>();
        for (ApiEndpoint ep : endpoints) {
            String entity = dependencyEngine.extractEntityNameFromPath(ep.getPath());
            entityMap.computeIfAbsent(entity, k -> new ArrayList<>()).add(ep);
        }

        int caseOrder = 1;
        Set<String> plannedEndpointIds = new HashSet<>();

        // ------------------------------------------------------------------
        // Stage 1: Plan CRUD workflows for entities supporting stateful lifecycles
        // ------------------------------------------------------------------
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
                int postExpected = extractExpectedStatus(postEp, 201);
                TestStep createStep = createStepWithParams(crudCase, postEp, stepOrder++, "CREATE " + entity, "POST", postEp.getPath(), postExpected, testRun.getId(), entity, openApiSchemas);
                steps.add(createStep);
                plannedEndpointIds.add(postEp.getId());

                // Step 2: GET /entity/{id} (READ after create)
                String getPath = replacePathParamWithVar(getByIdEp.getPath(), "{{" + entity + ".id}}");
                int getExpected = extractExpectedStatus(getByIdEp, 200);
                TestStep readStep1 = createStepWithParams(crudCase, getByIdEp, stepOrder++, "READ " + entity + " by ID", "GET", getPath, getExpected, testRun.getId(), entity, openApiSchemas);
                steps.add(readStep1);

                // Step 2b: Conditional ETag GET /entity/{id} only if contract specifies ETag or 304
                if (getByIdEp.getResponseSchemas() != null &&
                        (getByIdEp.getResponseSchemas().toLowerCase().contains("etag") ||
                         getByIdEp.getResponseSchemas().contains("304"))) {
                    TestStep condStep = createStepWithParams(crudCase, getByIdEp, stepOrder++, "CONDITIONAL READ " + entity + " (If-None-Match)", "GET", getPath, 304, testRun.getId(), entity, openApiSchemas);
                    condStep.setRequestHeaders("If-None-Match: {{" + entity + ".etag}}");
                    steps.add(condStep);
                }

                // Step 3: PATCH/PUT /entity/{id} (UPDATE)
                if (updateEp != null) {
                    String updatePath = replacePathParamWithVar(updateEp.getPath(), "{{" + entity + ".id}}");
                    int updateExpected = extractExpectedStatus(updateEp, 200);
                    TestStep updateStep = createStepWithParams(crudCase, updateEp, stepOrder++, "UPDATE " + entity, updateEp.getMethod(), updatePath, updateExpected, testRun.getId(), entity, openApiSchemas);
                    steps.add(updateStep);
                    plannedEndpointIds.add(updateEp.getId());

                    // Step 4: GET /entity/{id} (READ after update)
                    TestStep readStep2 = createStepWithParams(crudCase, getByIdEp, stepOrder++, "VERIFY UPDATE " + entity, "GET", getPath, getExpected, testRun.getId(), entity, openApiSchemas);
                    steps.add(readStep2);
                }

                // Step 5: DELETE /entity/{id} (DELETE)
                if (deleteEp != null) {
                    String deletePath = replacePathParamWithVar(deleteEp.getPath(), "{{" + entity + ".id}}");
                    int deleteExpected = extractExpectedStatus(deleteEp, 204);
                    TestStep deleteStep = createStepWithParams(crudCase, deleteEp, stepOrder++, "DELETE " + entity, "DELETE", deletePath, deleteExpected, testRun.getId(), entity, openApiSchemas);
                    steps.add(deleteStep);
                    plannedEndpointIds.add(deleteEp.getId());

                    // Step 6: GET /entity/{id} (VERIFY 404 NOT FOUND)
                    TestStep verify404Step = createStepWithParams(crudCase, getByIdEp, stepOrder++, "VERIFY 404 AFTER DELETE " + entity, "GET", getPath, 404, testRun.getId(), entity, openApiSchemas);
                    steps.add(verify404Step);
                }

                plannedEndpointIds.add(getByIdEp.getId());
                testCases.add(crudCase);
                stepsByCaseId.put(crudCase.getId(), steps);
            }
        }

        // ------------------------------------------------------------------
        // Stage 2: Plan Single-Endpoint checks for ALL remaining endpoints (100% coverage)
        // ------------------------------------------------------------------
        for (ApiEndpoint ep : endpoints) {
            if (!plannedEndpointIds.contains(ep.getId())) {
                String entity = dependencyEngine.extractEntityNameFromPath(ep.getPath());
                int expectedStatus = extractExpectedStatus(ep, "DELETE".equalsIgnoreCase(ep.getMethod()) ? 204 : ("POST".equalsIgnoreCase(ep.getMethod()) ? 201 : 200));

                TestCase singleCase = new TestCase();
                singleCase.setId(UUID.randomUUID().toString());
                singleCase.setTestRun(testRun);
                singleCase.setName("Contract: " + ep.getMethod() + " " + ep.getPath());
                singleCase.setDescription("Deterministic contract verification for " + ep.getMethod() + " " + ep.getPath());
                singleCase.setScenarioType("SINGLE_ENDPOINT");
                singleCase.setCategory("POSITIVE_CONTRACT");
                singleCase.setExecutionOrder(caseOrder++);
                singleCase.setStatus(StepStatus.PENDING);
                singleCase.setCreatedAt(OffsetDateTime.now());

                TestStep step = createStepWithParams(singleCase, ep, 1, ep.getMethod() + " " + ep.getPath(), ep.getMethod(), ep.getPath(), expectedStatus, testRun.getId(), entity, openApiSchemas);
                testCases.add(singleCase);
                stepsByCaseId.put(singleCase.getId(), Collections.singletonList(step));
                plannedEndpointIds.add(ep.getId());
            }
        }

        // ------------------------------------------------------------------
        // Stage 3: Plan Pagination & Filter Tests for Collection Endpoints
        // ------------------------------------------------------------------
        for (ApiEndpoint ep : endpoints) {
            if ("GET".equalsIgnoreCase(ep.getMethod()) && !ep.getPath().contains("{")
                    && ep.getParameters() != null
                    && (ep.getParameters().toLowerCase().contains("page") || ep.getParameters().toLowerCase().contains("limit") || ep.getParameters().toLowerCase().contains("offset"))) {
                TestCase pageCase = new TestCase();
                pageCase.setId(UUID.randomUUID().toString());
                pageCase.setTestRun(testRun);
                pageCase.setName("Pagination: " + ep.getPath());
                pageCase.setDescription("Query pagination, boundary limits, and filter verification");
                pageCase.setScenarioType("PAGINATION_AND_FILTERING");
                pageCase.setCategory("PAGINATION_FILTER");
                pageCase.setExecutionOrder(caseOrder++);
                pageCase.setStatus(StepStatus.PENDING);
                pageCase.setCreatedAt(OffsetDateTime.now());

                List<TestStep> pageSteps = new ArrayList<>();
                int pOrder = 1;
                int exp = extractExpectedStatus(ep, 200);

                TestStep p1 = createStepWithParams(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Page 1)", "GET", ep.getPath() + "?page=1&pageSize=10", exp, testRun.getId(), null, openApiSchemas);
                pageSteps.add(p1);

                TestStep p2 = createStepWithParams(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Page 2)", "GET", ep.getPath() + "?page=2&pageSize=10", exp, testRun.getId(), null, openApiSchemas);
                pageSteps.add(p2);

                TestStep p3 = createStepWithParams(pageCase, ep, pOrder++, "GET " + ep.getPath() + " (Filter & Sort)", "GET", ep.getPath() + "?search=test&sort=asc", exp, testRun.getId(), null, openApiSchemas);
                pageSteps.add(p3);

                testCases.add(pageCase);
                stepsByCaseId.put(pageCase.getId(), pageSteps);
            }
        }

        // ------------------------------------------------------------------
        // Stage 4: Plan Negative & Boundary Fuzzing Scenarios for request bodies
        // ------------------------------------------------------------------
        for (ApiEndpoint ep : endpoints) {
            if (("POST".equalsIgnoreCase(ep.getMethod()) || "PUT".equalsIgnoreCase(ep.getMethod()) || "PATCH".equalsIgnoreCase(ep.getMethod()))
                    && ep.getRequestBodySchema() != null) {

                try {
                    Schema<?> schema = objectMapper.readValue(ep.getRequestBodySchema(), Schema.class);
                    String validBody = dataGenerator.generateJsonString(schema, testRun.getId(), openApiSchemas);
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
                } catch (Exception e) {
                    log.debug("Negative data generation skipped for {}: {}", ep.getPath(), e.getMessage());
                }
            }
        }

        return new PlanResult(testCases, stepsByCaseId);
    }

    public int extractExpectedStatus(ApiEndpoint endpoint, int defaultFallback) {
        if (endpoint.getResponseSchemas() == null || endpoint.getResponseSchemas().isBlank()) {
            return defaultFallback;
        }
        try {
            JsonNode root = objectMapper.readTree(endpoint.getResponseSchemas());
            if (root.isObject()) {
                int lowest2xx = Integer.MAX_VALUE;
                Iterator<String> fieldNames = root.fieldNames();
                while (fieldNames.hasNext()) {
                    String field = fieldNames.next();
                    try {
                        int code = Integer.parseInt(field.trim());
                        if (code >= 200 && code < 300 && code < lowest2xx) {
                            lowest2xx = code;
                        }
                    } catch (NumberFormatException ignored) {}
                }
                if (lowest2xx != Integer.MAX_VALUE) {
                    return lowest2xx;
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse response schemas for {}: {}", endpoint.getPath(), e.getMessage());
        }
        return defaultFallback;
    }

    private TestStep createStepWithParams(TestCase testCase, ApiEndpoint endpoint, int order, String name,
                                          String method, String initialPath, int expectedStatus, String runId, String entityName) {
        return createStepWithParams(testCase, endpoint, order, name, method, initialPath, expectedStatus, runId, entityName, null);
    }

    private TestStep createStepWithParams(TestCase testCase, ApiEndpoint endpoint, int order, String name,
                                          String method, String initialPath, int expectedStatus, String runId, String entityName,
                                          Map<String, Schema> openApiSchemas) {
        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(testCase);
        step.setApiEndpoint(endpoint);
        step.setStepOrder(order);
        step.setName(name);
        step.setMethod(method);
        step.setExpectedStatus(expectedStatus);
        step.setStatus(StepStatus.PENDING);

        String path = initialPath;
        Map<String, String> headers = new LinkedHashMap<>();
        Random random = new Random((runId + endpoint.getPath() + order).hashCode());

        // Process OpenAPI parameters (path, query, header, cookie)
        if (endpoint.getParameters() != null && !endpoint.getParameters().isBlank()) {
            try {
                JsonNode paramsNode = objectMapper.readTree(endpoint.getParameters());
                if (paramsNode.isArray()) {
                    List<String> queryParams = new ArrayList<>();
                    for (JsonNode p : paramsNode) {
                        String in = p.has("in") ? p.get("in").asText() : "";
                        String pName = p.has("name") ? p.get("name").asText() : "";
                        boolean required = p.has("required") && p.get("required").asBoolean();
                        JsonNode schemaNode = p.get("schema");

                        String val = "test_" + pName;
                        if (schemaNode != null && !schemaNode.isNull()) {
                            try {
                                Schema<?> schema = objectMapper.treeToValue(schemaNode, Schema.class);
                                Object gen = dataGenerator.generateValueForSchema(schema, pName, random, runId);
                                if (gen != null) val = gen.toString();
                            } catch (Exception ignored) {}
                        }

                        if ("path".equalsIgnoreCase(in)) {
                            if (entityName != null && ("id".equalsIgnoreCase(pName) || pName.toLowerCase().endsWith("id"))) {
                                path = path.replace("{" + pName + "}", "{{" + entityName + "." + pName + "}}");
                            } else {
                                path = path.replace("{" + pName + "}", val);
                            }
                        } else if ("query".equalsIgnoreCase(in)) {
                            if (required || queryParams.isEmpty()) {
                                queryParams.add(pName + "=" + val);
                            }
                        } else if ("header".equalsIgnoreCase(in)) {
                            headers.put(pName, val);
                        } else if ("cookie".equalsIgnoreCase(in)) {
                            headers.put("Cookie", pName + "=" + val);
                        }
                    }

                    if (!queryParams.isEmpty() && !path.contains("?")) {
                        path += "?" + String.join("&", queryParams);
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing parameters for {}: {}", endpoint.getPath(), e.getMessage());
            }
        }

        // Clean up any remaining path template variables
        if (path.contains("{")) {
            Matcher m = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}").matcher(path);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String paramName = m.group(1);
                if (entityName != null && ("id".equalsIgnoreCase(paramName) || paramName.toLowerCase().endsWith("id"))) {
                    m.appendReplacement(sb, "{{" + entityName + "." + paramName + "}}");
                } else {
                    m.appendReplacement(sb, "1");
                }
            }
            m.appendTail(sb);
            path = sb.toString();
        }

        step.setPathTemplate(path);

        // Serialize headers if present
        if (!headers.isEmpty()) {
            try {
                step.setRequestHeaders(objectMapper.writeValueAsString(headers));
            } catch (Exception ignored) {}
        }

        // Generate synthetic body if method is POST, PUT, or PATCH and requestBodySchema is present
        if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))
                && endpoint.getRequestBodySchema() != null) {
            try {
                Schema<?> schema = objectMapper.readValue(endpoint.getRequestBodySchema(), Schema.class);
                String bodyJson = dataGenerator.generateJsonString(schema, runId, openApiSchemas);
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
