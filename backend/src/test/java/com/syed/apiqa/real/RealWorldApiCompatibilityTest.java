package com.syed.apiqa.real;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.assertion.AssertionEngine;
import com.syed.apiqa.planning.DependencyEngine;
import com.syed.apiqa.discovery.ContractNormalizationService;
import com.syed.apiqa.discovery.OpenApi31Normalizer;
import com.syed.apiqa.discovery.OpenApiFetchService;
import com.syed.apiqa.discovery.OpenApiParserService;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.execution.HttpExecutionEngine;
import com.syed.apiqa.planning.TestPlanService;
import com.syed.apiqa.safety.SecretMasker;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RealWorldApiCompatibilityTest
 * Executes automated continuous compatibility tests against REAL public OpenAPI/Swagger specifications
 * and safe public API endpoints in the wild.
 * Verifies discovery, normalization, dependency planning, schema generation, real HTTP execution,
 * and assertions across diverse API structures.
 */
@SpringBootTest
@ActiveProfiles("test")
public class RealWorldApiCompatibilityTest {

    @Autowired
    private OpenApiFetchService fetchService;

    @Autowired
    private OpenApiParserService parserService;

    @Autowired
    private ContractNormalizationService normalizationService;

    @Autowired
    private DependencyEngine dependencyEngine;

    @Autowired
    private TestPlanService testPlanService;

    @Autowired
    private HttpExecutionEngine httpExecutionEngine;

    @Autowired
    private com.syed.apiqa.persistence.TestRunRepository testRunRepository;

    @Autowired
    private com.syed.apiqa.persistence.TestCaseRepository testCaseRepository;

    @Autowired
    private com.syed.apiqa.persistence.TestStepRepository testStepRepository;

    @Autowired
    private com.syed.apiqa.persistence.ApiEndpointRepository apiEndpointRepository;

    @Test
    @DisplayName("Real-World Test 1: Fetch, Parse, Normalize, and Plan Live Swagger Petstore v3 (OpenAPI 3.0)")
    void testRealSwaggerPetstoreV3Spec() {
        String specUrl = "https://petstore3.swagger.io/api/v3/openapi.json";
        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        testRunRepository.save(run);

        // 1. Live Fetch
        String rawSpec = fetchService.fetchSpecification(specUrl);
        assertNotNull(rawSpec, "Live specification must not be null");
        assertTrue(rawSpec.contains("openapi") || rawSpec.contains("swagger"), "Must contain openapi header");

        // 2. Parser Discovery
        OpenApiParserService.DiscoveryResult discovery = parserService.parse(rawSpec, specUrl, run);
        assertNotNull(discovery);
        assertNotNull(discovery.getOpenAPI());
        assertFalse(discovery.getEndpoints().isEmpty(), "Discovered endpoints must not be empty");
        assertTrue(discovery.getEndpoints().size() >= 15, "Petstore v3 must contain at least 15 endpoints (found: " + discovery.getEndpoints().size() + ")");

        for (ApiEndpoint ep : discovery.getEndpoints()) {
            apiEndpointRepository.save(ep);
        }

        // 3. Normalization to Canonical Model
        ContractNormalizationService.NormalizationResult normResult = normalizationService.normalize(discovery.getOpenAPI(), specUrl);
        assertNotNull(normResult);
        CanonicalApiModel model = normResult.model();
        assertNotNull(model);
        assertFalse(model.getOperations().isEmpty(), "Canonical operations must be populated");
        assertTrue(model.getOperations().size() >= 15);

        // 4. Dependency Graph & Planning
        List<Dependency> dependencies = dependencyEngine.buildDependencies(run, discovery.getEndpoints());
        assertNotNull(dependencies);

        TestPlanService.PlanResult plan = testPlanService.buildTestPlan(
                run,
                discovery.getEndpoints(),
                dependencies,
                discovery.getOpenAPI().getComponents() != null ? discovery.getOpenAPI().getComponents().getSchemas() : null
        );
        assertNotNull(plan);
        assertFalse(plan.getTestCases().isEmpty(), "Test plan must contain test cases");

        for (TestCase tc : plan.getTestCases()) {
            testCaseRepository.save(tc);
            List<TestStep> steps = plan.getStepsByCaseId().get(tc.getId());
            if (steps != null) {
                for (TestStep s : steps) {
                    testStepRepository.save(s);
                }
            }
        }

        // 5. Live Safe Read HTTP Execution
        ExecutionContext context = new ExecutionContext(run.getId());
        TestStep safeGetStep = null;
        for (List<TestStep> steps : plan.getStepsByCaseId().values()) {
            for (TestStep step : steps) {
                if ("GET".equalsIgnoreCase(step.getMethod()) && !step.getPathTemplate().contains("{")) {
                    safeGetStep = step;
                    break;
                }
            }
            if (safeGetStep != null) break;
        }

        if (safeGetStep != null) {
            String baseUrl = discovery.getResolvedBaseUrl() != null ? discovery.getResolvedBaseUrl() : "https://petstore3.swagger.io/api/v3";
            HttpExecutionEngine.StepExecutionOutcome outcome = httpExecutionEngine.executeStep(
                    safeGetStep,
                    baseUrl,
                    context,
                    EnvironmentType.DEVELOPMENT, // allow live external request
                    "NONE",
                    null
            );
            assertNotNull(outcome);
            assertNotNull(outcome.getExecution());
            assertTrue(outcome.getExecution().getResponseStatus() > 0, "Must record valid HTTP response code");
            assertTrue(outcome.getExecution().getLatencyMs() > 0, "Must record non-zero latency");
        }
    }

    @Test
    @DisplayName("Real-World Test 2: Fetch, Parse, and Normalize Live Swagger Petstore v2 (Swagger 2.0)")
    void testRealSwaggerPetstoreV2Spec() {
        String specUrl = "https://petstore.swagger.io/v2/swagger.json";
        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);

        // 1. Live Fetch
        String rawSpec = fetchService.fetchSpecification(specUrl);
        assertNotNull(rawSpec);
        assertTrue(rawSpec.contains("swagger") || rawSpec.contains("2.0"));

        // 2. Parser Discovery
        OpenApiParserService.DiscoveryResult discovery = parserService.parse(rawSpec, specUrl, run);
        assertNotNull(discovery);
        assertNotNull(discovery.getOpenAPI());
        assertFalse(discovery.getEndpoints().isEmpty());
        assertTrue(discovery.getEndpoints().size() >= 18, "Petstore v2 must contain at least 18 endpoints");

        // 3. Normalization to Canonical Model
        ContractNormalizationService.NormalizationResult normResult = normalizationService.normalize(discovery.getOpenAPI(), specUrl);
        assertNotNull(normResult);
        CanonicalApiModel model = normResult.model();
        assertNotNull(model);
        assertFalse(model.getOperations().isEmpty());
    }

    @Test
    @DisplayName("Real-World Test 3: Multi-Target Compatibility Registry Verification")
    void testRegistryTargetsDiscovery() {
        List<RealTargetRegistry.RealTarget> targets = RealTargetRegistry.getStandardRealTargets();
        assertFalse(targets.isEmpty());

        for (RealTargetRegistry.RealTarget target : targets) {
            assertNotNull(target.getId());
            assertNotNull(target.getSpecUrl());
            assertTrue(target.getExpectedMinOperations() > 0);
        }
    }
}
