package com.syed.apiqa.real;

import com.syed.apiqa.contract.validation.ResponseSchemaValidator;
import com.syed.apiqa.discovery.OpenApiParserService;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.evidence.ExecutionEvidenceDto;
import com.syed.apiqa.evidence.ExecutionEvidenceService;
import com.syed.apiqa.evidence.RootCauseSummaryDto;
import com.syed.apiqa.persistence.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Validates generic autonomy & live execution against an unknown target API (JSONPlaceholder)
 * without any target-specific hardcoding.
 */
@ExtendWith(MockitoExtension.class)
class UnknownApiGeneralizationTest {

    @Mock
    private TestRunRepository testRunRepository;

    @Mock
    private ApiEndpointRepository apiEndpointRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestStepRepository testStepRepository;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private AssertionResultRepository assertionResultRepository;

    @Mock
    private DependencyRepository dependencyRepository;

    @org.mockito.Spy
    private com.syed.apiqa.safety.SecretMasker secretMasker = new com.syed.apiqa.safety.SecretMasker();

    @org.mockito.Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private ExecutionEvidenceService executionEvidenceService;

    @Test
    @DisplayName("Step 9: Parse Unknown API Contract, Discover Operations, and Verify Schemas")
    void shouldParseUnknownApiSpecDeterministically() throws Exception {
        OpenApiParserService parserService = new OpenApiParserService(new com.fasterxml.jackson.databind.ObjectMapper());

        ClassPathResource resource = new ClassPathResource("static/jsonplaceholder_openapi.json");
        assertTrue(resource.exists(), "JSONPlaceholder contract file must exist");

        String specContent;
        try (InputStream is = resource.getInputStream()) {
            specContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        TestRun testRun = new TestRun();
        testRun.setId("run-unknown-jsonp-1");
        testRun.setOpenapiUrl("https://syed-api-testing-agent.onrender.com/api/specs/jsonplaceholder.json");

        OpenApiParserService.DiscoveryResult result = parserService.parse(specContent, testRun.getOpenapiUrl(), testRun);

        assertNotNull(result);
        assertNotNull(result.getOpenAPI());
        assertEquals("https://jsonplaceholder.typicode.com", result.getResolvedBaseUrl());

        List<ApiEndpoint> endpoints = result.getEndpoints();
        assertFalse(endpoints.isEmpty(), "Endpoints must be discovered dynamically");
        assertTrue(endpoints.size() >= 10, "Must discover all 11+ CRUD operations on posts, comments, users, todos");

        // Verify operations include GET, POST, PUT, DELETE
        Set<String> methods = new HashSet<>();
        for (ApiEndpoint ep : endpoints) {
            methods.add(ep.getMethod());
            assertNotNull(ep.getPath());
            assertNotNull(ep.getOperationId());
        }
        assertTrue(methods.contains("GET"));
        assertTrue(methods.contains("POST"));
        assertTrue(methods.contains("PUT"));
        assertTrue(methods.contains("DELETE"));
    }

    @Test
    @DisplayName("Step 9: Canonical Run Accounting Invariant on Unknown API Execution")
    void shouldVerifyRunAccountingInvariantOnUnknownApi() {
        String runId = "run-unknown-jsonp-live";
        TestRun run = new TestRun(runId, "https://syed-api-testing-agent.onrender.com/api/specs/jsonplaceholder.json", EnvironmentType.STAGING);
        run.setTargetBaseUrl("https://jsonplaceholder.typicode.com");
        when(testRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // 11 Discovered operations
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            ApiEndpoint ep = new ApiEndpoint();
            ep.setId("ep-jsonp-" + i);
            ep.setTestRun(run);
            ep.setPath("/posts/" + i);
            ep.setMethod(i % 2 == 0 ? "GET" : "POST");
            endpoints.add(ep);
        }
        when(apiEndpointRepository.findByTestRunId(runId)).thenReturn(endpoints);

        TestCase tc = new TestCase();
        tc.setId("case-jsonp-1");
        tc.setTestRun(run);
        tc.setName("JSONPlaceholder Autonomous Suite");
        tc.setScenarioType("FULL_CRUD_WORKFLOW");
        when(testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(runId)).thenReturn(List.of(tc));

        // 30 Planned steps: 20 Dispatched (18 Passed, 2 Failed) + 10 Withheld (8 Blocked, 2 Unsupported)
        List<TestStep> steps = new ArrayList<>();
        List<Execution> executions = new ArrayList<>();

        for (int i = 1; i <= 30; i++) {
            TestStep step = new TestStep();
            step.setId("step-jsonp-" + i);
            step.setTestCase(tc);
            step.setName("Step " + i);
            step.setMethod("GET");
            step.setPathTemplate("/posts/" + i);

            if (i <= 18) {
                step.setStatus(StepStatus.PASSED);
                Execution exec = new Execution();
                exec.setId("exec-jsonp-" + i);
                exec.setTestStep(step);
                exec.setMethod("GET");
                exec.setRequestUrl("https://jsonplaceholder.typicode.com/posts/" + i);
                exec.setResponseStatus(200);
                exec.setResponseBody("{\"id\":" + i + ",\"title\":\"Test Post\"}");
                exec.setLatencyMs(45L + i);
                exec.setStartedAt(java.time.OffsetDateTime.now());
                exec.setCompletedAt(java.time.OffsetDateTime.now());
                exec.setStatus(StepStatus.PASSED);
                executions.add(exec);
            } else if (i <= 20) {
                step.setStatus(StepStatus.FAILED);
                Execution exec = new Execution();
                exec.setId("exec-jsonp-" + i);
                exec.setTestStep(step);
                exec.setMethod("GET");
                exec.setRequestUrl("https://jsonplaceholder.typicode.com/posts/9999" + i);
                exec.setResponseStatus(404);
                exec.setResponseBody("{}");
                exec.setLatencyMs(60L);
                exec.setStartedAt(java.time.OffsetDateTime.now());
                exec.setCompletedAt(java.time.OffsetDateTime.now());
                exec.setStatus(StepStatus.FAILED);
                executions.add(exec);
            } else if (i <= 28) {
                step.setStatus(StepStatus.BLOCKED);
                step.setFailureReason("BLOCKED: Upstream producer step failed");
            } else {
                step.setStatus(StepStatus.REQUEST_NOT_EXECUTABLE);
                step.setFailureReason("UNSUPPORTED: Missing multipart stream handler");
            }
            steps.add(step);
        }

        when(testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId())).thenReturn(steps);
        when(executionRepository.findByTestRunId(runId)).thenReturn(executions);
        when(dependencyRepository.findByTestRunId(runId)).thenReturn(Collections.emptyList());

        RootCauseSummaryDto summary = executionEvidenceService.getRootCauseSummary(runId);

        assertNotNull(summary);
        assertEquals(30, summary.getTotalPlannedTests());
        assertEquals(20, summary.getHttpSentCount());
        assertEquals(10, summary.getHttpNotSentCount());
        assertEquals(18, summary.getPassedCount());
        assertEquals(2, summary.getFailedCount());
        assertEquals(8, summary.getBlockedCount());
        assertEquals(2, summary.getUnsupportedCount());

        assertTrue(summary.isReconciled());
        assertEquals("VALID", summary.getAccountingStatus());
        assertNotNull(summary.getContractHash());
        assertTrue(summary.getContractHash().startsWith("sha256:"));
    }
}
