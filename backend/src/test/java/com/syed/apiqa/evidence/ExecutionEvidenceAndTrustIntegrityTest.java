package com.syed.apiqa.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.safety.SecretMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ExecutionEvidenceAndTrustIntegrityTest {

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestStepRepository testStepRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private AssertionResultRepository assertionResultRepository;

    @Autowired
    private DependencyRepository dependencyRepository;

    @Autowired
    private ExecutionEvidenceService executionEvidenceService;

    @Autowired
    private SecretMasker secretMasker;

    @Autowired
    private ObjectMapper objectMapper;

    private String runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("Trust Principle #1: Canonical Evidence Model is Fully Populated and Verifiable")
    void testCanonicalEvidenceModelCompleteness() {
        TestRun run = new TestRun(runId, "https://api.example.com/openapi.json", EnvironmentType.DEVELOPMENT);
        run.setTargetBaseUrl("https://api.example.com");
        testRunRepository.save(run);

        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName("User Account Lifecycle");
        tc.setScenarioType("CRUD_WORKFLOW");
        tc.setExecutionOrder(1);
        testCaseRepository.save(tc);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(tc);
        step.setName("Create User Account");
        step.setMethod("POST");
        step.setPathTemplate("/api/v1/users");
        step.setResolvedUrl("https://api.example.com/api/v1/users");
        step.setRequestBody("{\"username\":\"syed_tester\",\"password\":\"SuperSecret123!\",\"email\":\"test@example.com\"}");
        step.setRequestHeaders("{\"Authorization\":\"Bearer syed_sec_v1.token999xyz\",\"Content-Type\":\"application/json\"}");
        step.setExpectedStatus(201);
        step.setStatus(StepStatus.PASSED);
        testStepRepository.save(step);

        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setTestStep(step);
        exec.setMethod("POST");
        exec.setRequestUrl("https://api.example.com/api/v1/users");
        exec.setRequestHeaders(step.getRequestHeaders());
        exec.setRequestBody(step.getRequestBody());
        exec.setResponseStatus(201);
        exec.setResponseHeaders("{\"Content-Type\":\"application/json\",\"Location\":\"/api/v1/users/usr_123\"}");
        exec.setResponseBody("{\"id\":\"usr_123\",\"username\":\"syed_tester\",\"status\":\"ACTIVE\"}");
        exec.setLatencyMs(142L);
        exec.setStartedAt(OffsetDateTime.now().minusSeconds(1));
        exec.setCompletedAt(OffsetDateTime.now());
        exec.setStatus(StepStatus.PASSED);
        executionRepository.save(exec);

        AssertionResult assertion = new AssertionResult();
        assertion.setId(UUID.randomUUID().toString());
        assertion.setExecution(exec);
        assertion.setAssertionType(AssertionType.STATUS_CODE);
        assertion.setTargetField("response.status");
        assertion.setExpectedValue("201");
        assertion.setActualValue("201");
        assertion.setPassed(true);
        assertionResultRepository.save(assertion);

        // Fetch Canonical Evidence
        List<ExecutionEvidenceDto> evidenceList = executionEvidenceService.getEvidenceForRun(runId);
        assertNotNull(evidenceList);
        assertEquals(1, evidenceList.size());

        ExecutionEvidenceDto ev = evidenceList.get(0);

        // 1. Identity
        assertEquals(runId, ev.getRunId());
        assertEquals(step.getId(), ev.getStepId());
        assertEquals("POST", ev.getMethod());
        assertEquals("/api/v1/users", ev.getPathTemplate());

        // 2. Execution & HTTP_SENT Truth
        assertTrue(ev.isHttpSent(), "HTTP_SENT must be true for real wire execution");
        assertEquals(201, ev.getResponseStatus());
        assertEquals(142L, ev.getLatencyMs());
        assertEquals("PASSED", ev.getStatus());

        // 3. Secret Redaction
        assertNotNull(ev.getRequestHeaders());
        String authHeader = ev.getRequestHeaders().get("Authorization");
        assertNotNull(authHeader);
        assertFalse(authHeader.contains("syed_sec_v1.token999xyz"), "Raw Bearer token must be redacted!");
        assertTrue(authHeader.contains("••••"), "Token must be masked with bullet preview");

        String reqBody = ev.getRequestBody();
        assertNotNull(reqBody);
        assertFalse(reqBody.contains("SuperSecret123!"), "Password in request body must be redacted!");

        // 4. Response & Assertions
        assertNotNull(ev.getResponseBody());
        assertTrue(ev.getResponseBody().contains("usr_123"));
        assertEquals(1, ev.getAssertions().size());
        assertTrue(ev.getAssertions().get(0).isPassed());

        // 5. Diagnosis
        assertNotNull(ev.getCustomerExplanation());
        assertNotNull(ev.getSuggestedRemediation());
    }

    @Test
    @DisplayName("Trust Principle #2: Blocked Step Proof with HTTP_SENT=false and Actionable Root Cause")
    void testBlockedStepEvidenceIntegrity() {
        TestRun run = new TestRun(runId, "https://api.example.com/openapi.json", EnvironmentType.DEVELOPMENT);
        testRunRepository.save(run);

        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName("Invoice Settlement");
        tc.setScenarioType("CONTRACT_PROBE");
        testCaseRepository.save(tc);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(tc);
        step.setName("Delete Invoice");
        step.setMethod("DELETE");
        step.setPathTemplate("/api/v1/invoices/{id}");
        step.setStatus(StepStatus.BLOCKED);
        step.setFailureReason("BLOCKED_BY_AUTHENTICATION: Required capability [billing.admin] not present in active profile");
        testStepRepository.save(step);

        List<ExecutionEvidenceDto> evidenceList = executionEvidenceService.getEvidenceForRun(runId);
        assertEquals(1, evidenceList.size());

        ExecutionEvidenceDto ev = evidenceList.get(0);
        assertFalse(ev.isHttpSent(), "Blocked steps must strictly have HTTP_SENT = false");
        assertNull(ev.getResponseStatus(), "Blocked steps must have no HTTP status code");
        assertEquals("BLOCKED", ev.getStatus());
        assertEquals("AUTHENTICATION_REQUIRED", ev.getClassification());
        assertNotNull(ev.getSuggestedRemediation());
        assertTrue(ev.getSuggestedRemediation().contains("Bearer Token") || ev.getSuggestedRemediation().contains("credentials"));
    }

    @Test
    @DisplayName("Trust Principle #3: Root-Cause Aggregation Groups Multiple Failures Accurately")
    void testRootCauseSummaryAggregation() {
        TestRun run = new TestRun(runId, "https://api.example.com/openapi.json", EnvironmentType.DEVELOPMENT);
        testRunRepository.save(run);

        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName("Bulk Test Execution");
        tc.setScenarioType("CONTRACT_PROBE");
        testCaseRepository.save(tc);

        // Step 1: 401 Auth Failure
        TestStep step1 = new TestStep();
        step1.setId(UUID.randomUUID().toString());
        step1.setTestCase(tc);
        step1.setName("Get Secure User");
        step1.setMethod("GET");
        step1.setPathTemplate("/api/v1/users/me");
        step1.setStatus(StepStatus.FAILED);
        testStepRepository.save(step1);

        Execution exec1 = new Execution();
        exec1.setId(UUID.randomUUID().toString());
        exec1.setTestStep(step1);
        exec1.setMethod("GET");
        exec1.setRequestUrl("https://api.example.com/api/v1/users/me");
        exec1.setResponseStatus(401);
        exec1.setLatencyMs(80L);
        exec1.setStartedAt(OffsetDateTime.now());
        exec1.setCompletedAt(OffsetDateTime.now());
        exec1.setStatus(StepStatus.FAILED);
        executionRepository.save(exec1);

        // Step 2: 401 Auth Failure
        TestStep step2 = new TestStep();
        step2.setId(UUID.randomUUID().toString());
        step2.setTestCase(tc);
        step2.setName("Get Secure Orders");
        step2.setMethod("GET");
        step2.setPathTemplate("/api/v1/orders");
        step2.setStatus(StepStatus.FAILED);
        testStepRepository.save(step2);

        Execution exec2 = new Execution();
        exec2.setId(UUID.randomUUID().toString());
        exec2.setTestStep(step2);
        exec2.setMethod("GET");
        exec2.setRequestUrl("https://api.example.com/api/v1/orders");
        exec2.setResponseStatus(401);
        exec2.setLatencyMs(95L);
        exec2.setStartedAt(OffsetDateTime.now());
        exec2.setCompletedAt(OffsetDateTime.now());
        exec2.setStatus(StepStatus.FAILED);
        executionRepository.save(exec2);

        // Step 3: Blocked by upstream failure
        TestStep step3 = new TestStep();
        step3.setId(UUID.randomUUID().toString());
        step3.setTestCase(tc);
        step3.setName("Update Order Status");
        step3.setMethod("PUT");
        step3.setPathTemplate("/api/v1/orders/{orderId}");
        step3.setStatus(StepStatus.BLOCKED);
        step3.setFailureReason("BLOCKED: Upstream producer step failed");
        testStepRepository.save(step3);

        RootCauseSummaryDto summary = executionEvidenceService.getRootCauseSummary(runId);
        assertNotNull(summary);
        assertEquals(3, summary.getTotalPlannedTests());
        assertEquals(2, summary.getHttpSentCount());
        assertEquals(1, summary.getHttpNotSentCount());
        assertEquals(2, summary.getFailedCount());
        assertEquals(1, summary.getBlockedCount());

        // Verify failure grouping
        assertFalse(summary.getFailureGroups().isEmpty());
        RootCauseSummaryDto.RootCauseGroup authGroup = summary.getFailureGroups().get(0);
        assertEquals("AUTHORIZATION_DENIAL", authGroup.getCategory());
        assertEquals(2, authGroup.getAffectedCount());

        // Verify blocked grouping
        assertFalse(summary.getBlockedGroups().isEmpty());
        RootCauseSummaryDto.RootCauseGroup blockGroup = summary.getBlockedGroups().get(0);
        assertEquals("UPSTREAM_PRODUCER_FAILURE", blockGroup.getCategory());
        assertEquals(1, blockGroup.getAffectedCount());
    }
}
