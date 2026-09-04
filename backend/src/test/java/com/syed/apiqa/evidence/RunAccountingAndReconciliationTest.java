package com.syed.apiqa.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.safety.SecretMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class RunAccountingAndReconciliationTest {

    private TestRunRepository testRunRepository;
    private ApiEndpointRepository apiEndpointRepository;
    private TestCaseRepository testCaseRepository;
    private TestStepRepository testStepRepository;
    private ExecutionRepository executionRepository;
    private AssertionResultRepository assertionResultRepository;
    private DependencyRepository dependencyRepository;
    private SecretMasker secretMasker;
    private ObjectMapper objectMapper;
    private ExecutionEvidenceService executionEvidenceService;

    @BeforeEach
    void setUp() {
        testRunRepository = Mockito.mock(TestRunRepository.class);
        apiEndpointRepository = Mockito.mock(ApiEndpointRepository.class);
        testCaseRepository = Mockito.mock(TestCaseRepository.class);
        testStepRepository = Mockito.mock(TestStepRepository.class);
        executionRepository = Mockito.mock(ExecutionRepository.class);
        assertionResultRepository = Mockito.mock(AssertionResultRepository.class);
        dependencyRepository = Mockito.mock(DependencyRepository.class);
        secretMasker = new SecretMasker();
        objectMapper = new ObjectMapper();

        executionEvidenceService = new ExecutionEvidenceService(
                testRunRepository,
                apiEndpointRepository,
                testCaseRepository,
                testStepRepository,
                executionRepository,
                assertionResultRepository,
                dependencyRepository,
                secretMasker,
                objectMapper
        );
    }

    @Test
    @DisplayName("Exact Reconciliation Invariant: 65 Planned = 16 Dispatched (10 Passed + 6 Failed) + 49 Withheld (44 Blocked + 5 Unsupported)")
    void testExactReconciliationAccounting() {
        String runId = "run-truth-123";
        TestRun run = new TestRun(runId, "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        when(testRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // 20 Discovered OpenAPI endpoints
        List<ApiEndpoint> discoveredEndpoints = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            ApiEndpoint ep = new ApiEndpoint();
            ep.setId("ep-" + i);
            ep.setTestRun(run);
            ep.setPath("/api/resource/" + i);
            ep.setMethod((i % 2 == 0) ? "GET" : "POST");
            discoveredEndpoints.add(ep);
        }
        when(apiEndpointRepository.findByTestRunId(runId)).thenReturn(discoveredEndpoints);

        // 65 Planned Test Steps
        TestCase testCase = new TestCase();
        testCase.setId("case-1");
        testCase.setTestRun(run);
        testCase.setName("Full Test Plan");
        testCase.setScenarioType("FULL_SUITE");
        testCase.setExecutionOrder(1);
        when(testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(runId)).thenReturn(List.of(testCase));

        List<TestStep> steps = new ArrayList<>();
        List<Execution> executions = new ArrayList<>();

        // 10 PASSED (HTTP_SENT = true)
        for (int i = 1; i <= 10; i++) {
            TestStep s = new TestStep();
            s.setId("step-pass-" + i);
            s.setTestCase(testCase);
            s.setName("Step Pass " + i);
            s.setStepOrder(i);
            s.setMethod("GET");
            s.setPathTemplate("/api/resource/" + i);
            s.setExpectedStatus(200);
            s.setStatus(StepStatus.PASSED);
            steps.add(s);

            Execution exec = new Execution();
            exec.setId("exec-pass-" + i);
            exec.setTestStep(s);
            exec.setStartedAt(OffsetDateTime.now());
            exec.setCompletedAt(OffsetDateTime.now());
            exec.setResponseStatus(200);
            exec.setStatus(StepStatus.PASSED);
            exec.setLatencyMs(45L);
            exec.setResponseBody("{\"id\": " + i + ", \"status\": \"available\"}");
            executions.add(exec);
        }

        // 6 FAILED (HTTP_SENT = true)
        for (int i = 11; i <= 16; i++) {
            TestStep s = new TestStep();
            s.setId("step-fail-" + i);
            s.setTestCase(testCase);
            s.setName("Step Fail " + i);
            s.setStepOrder(i);
            s.setMethod("POST");
            s.setPathTemplate("/api/resource/" + i);
            s.setExpectedStatus(200);
            s.setStatus(StepStatus.FAILED);
            steps.add(s);

            Execution exec = new Execution();
            exec.setId("exec-fail-" + i);
            exec.setTestStep(s);
            exec.setStartedAt(OffsetDateTime.now());
            exec.setCompletedAt(OffsetDateTime.now());
            exec.setResponseStatus(500);
            exec.setStatus(StepStatus.FAILED);
            exec.setLatencyMs(120L);
            exec.setResponseBody("{\"error\": \"Internal Server Error\"}");
            executions.add(exec);
        }

        // 44 BLOCKED (HTTP_SENT = false)
        for (int i = 17; i <= 60; i++) {
            TestStep s = new TestStep();
            s.setId("step-block-" + i);
            s.setTestCase(testCase);
            s.setName("Step Blocked " + i);
            s.setStepOrder(i);
            s.setMethod("DELETE");
            s.setPathTemplate("/api/resource/" + i);
            s.setExpectedStatus(200);
            s.setStatus(StepStatus.BLOCKED);
            s.setFailureReason("Missing required authentication profile: Bearer token");
            steps.add(s);
        }

        // 5 UNSUPPORTED (HTTP_SENT = false)
        for (int i = 61; i <= 65; i++) {
            TestStep s = new TestStep();
            s.setId("step-unsupported-" + i);
            s.setTestCase(testCase);
            s.setName("Step Unsupported " + i);
            s.setStepOrder(i);
            s.setMethod("POST");
            s.setPathTemplate("/api/upload/" + i);
            s.setExpectedStatus(200);
            s.setStatus(StepStatus.REQUEST_NOT_EXECUTABLE);
            s.setFailureReason("Multipart/form-data upload probe not supported without binary fixture");
            steps.add(s);
        }

        when(testStepRepository.findByTestCaseIdOrderByStepOrderAsc("case-1")).thenReturn(steps);
        when(executionRepository.findByTestRunId(runId)).thenReturn(executions);

        // Execute Root Cause Summary & Invariant Verification
        RootCauseSummaryDto summary = executionEvidenceService.getRootCauseSummary(runId);

        assertNotNull(summary);
        assertEquals(20, summary.getDiscoveredOperations(), "Must discover exactly 20 operations");
        assertEquals(65, summary.getTotalPlannedTests(), "Must account for all 65 planned test cases");
        assertEquals(16, summary.getHttpSentCount(), "Must have exactly 16 wire-dispatched HTTP requests");
        assertEquals(49, summary.getHttpNotSentCount(), "Must have exactly 49 withheld non-dispatched steps");
        assertEquals(10, summary.getPassedCount(), "Must have exactly 10 passed tests");
        assertEquals(6, summary.getFailedCount(), "Must have exactly 6 failed tests");
        assertEquals(44, summary.getBlockedCount(), "Must have exactly 44 blocked tests");
        assertEquals(5, summary.getUnsupportedCount(), "Must have exactly 5 unsupported tests");

        assertTrue(summary.isReconciled(), "Accounting invariant must be satisfied");
        assertEquals("VALID", summary.getAccountingStatus());
        assertEquals("65 Planned = 16 Dispatched (10 Passed + 6 Failed) + 49 Withheld (44 Blocked + 5 Unsupported)",
                summary.getReconciliationEquation());
    }

    @Test
    @DisplayName("Strict Wire Guard: Reject marking un-dispatched step as PASSED")
    void testUndispatchedPassRejection() {
        String runId = "run-guard-456";
        TestRun run = new TestRun(runId, "https://api.example.com", EnvironmentType.STAGING);
        when(testRunRepository.findById(runId)).thenReturn(Optional.of(run));

        TestCase testCase = new TestCase();
        testCase.setId("case-guard");
        testCase.setTestRun(run);
        testCase.setName("Guard Test");
        testCase.setScenarioType("CONTRACT_PROBE");
        when(testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(runId)).thenReturn(List.of(testCase));

        // Step claims PASSED but has NO execution / NO HTTP_SENT
        TestStep s = new TestStep();
        s.setId("step-fake");
        s.setTestCase(testCase);
        s.setName("Fake Pass Step");
        s.setMethod("GET");
        s.setPathTemplate("/api/secure");
        s.setExpectedStatus(200);
        s.setStatus(StepStatus.PASSED);

        when(testStepRepository.findByTestCaseIdOrderByStepOrderAsc("case-guard")).thenReturn(List.of(s));
        when(executionRepository.findByTestRunId(runId)).thenReturn(List.of()); // 0 executions

        List<ExecutionEvidenceDto> evidence = executionEvidenceService.getEvidenceForRun(runId);
        assertEquals(1, evidence.size());

        ExecutionEvidenceDto dto = evidence.get(0);
        assertFalse(dto.isHttpSent(), "HTTP_SENT must be false when no wire execution occurred");
        assertNotEquals("PASSED", dto.getStatus(), "Un-dispatched step must NEVER be allowed status PASSED");
        assertEquals("BLOCKED", dto.getStatus());
    }

    @Test
    @DisplayName("Adversarial Secret Masking: Redact auth tokens while keeping business payloads intact")
    void testSecretRedactionVersusBusinessData() {
        String runId = "run-secret-789";
        TestRun run = new TestRun(runId, "https://api.example.com", EnvironmentType.STAGING);
        when(testRunRepository.findById(runId)).thenReturn(Optional.of(run));

        TestCase testCase = new TestCase();
        testCase.setId("case-payload");
        testCase.setTestRun(run);
        testCase.setName("Payload Test");
        testCase.setScenarioType("CRUD_WORKFLOW");
        when(testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(runId)).thenReturn(List.of(testCase));

        TestStep s = new TestStep();
        s.setId("step-order");
        s.setTestCase(testCase);
        s.setName("Create Order");
        s.setMethod("POST");
        s.setPathTemplate("/api/orders");
        s.setExpectedStatus(201);
        s.setStatus(StepStatus.PASSED);
        s.setRequestHeaders("{\"Authorization\": \"Bearer secret_live_jwt_token_12345\", \"X-Api-Key\": \"super_secret_key_abc\", \"Content-Type\": \"application/json\"}");
        s.setRequestBody("{\"customerId\": \"8472\", \"quantity\": 2, \"currency\": \"USD\", \"password\": \"mypassword123\"}");

        Execution exec = new Execution();
        exec.setId("exec-order");
        exec.setTestStep(s);
        exec.setStartedAt(OffsetDateTime.now());
        exec.setResponseStatus(201);
        exec.setStatus(StepStatus.PASSED);
        exec.setRequestHeaders(s.getRequestHeaders());
        exec.setRequestBody(s.getRequestBody());
        exec.setResponseBody("{\"id\": \"ord_123\", \"status\": \"created\", \"client_secret\": \"cs_live_secret_456\"}");

        when(testStepRepository.findByTestCaseIdOrderByStepOrderAsc("case-payload")).thenReturn(List.of(s));
        when(executionRepository.findByTestRunId(runId)).thenReturn(List.of(exec));

        List<ExecutionEvidenceDto> evidence = executionEvidenceService.getEvidenceForRun(runId);
        ExecutionEvidenceDto dto = evidence.get(0);

        // Header redaction
        assertEquals("Bearer syed_••••••••", dto.getRequestHeaders().get("Authorization"));
        assertEquals("••••••••", dto.getRequestHeaders().get("X-Api-Key"));
        assertEquals("application/json", dto.getRequestHeaders().get("Content-Type"));

        // Request body redaction
        assertTrue(dto.getRequestBody().contains("\"customerId\": \"8472\""), "Business payload customerId must be visible");
        assertTrue(dto.getRequestBody().contains("\"quantity\": 2"), "Business payload quantity must be visible");
        assertTrue(dto.getRequestBody().contains("\"currency\": \"USD\""), "Business payload currency must be visible");
        assertFalse(dto.getRequestBody().contains("mypassword123"), "Sensitive password must be redacted");
        assertTrue(dto.getRequestBody().contains("••••••••"));

        // Response body redaction
        assertTrue(dto.getResponseBody().contains("\"id\": \"ord_123\""));
        assertFalse(dto.getResponseBody().contains("cs_live_secret_456"), "Response client_secret must be redacted");
    }
}
