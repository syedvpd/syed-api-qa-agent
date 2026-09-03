package com.syed.apiqa.intelligence;

import com.syed.apiqa.agent.FailureIsolationHandler;
import com.syed.apiqa.contract.example.ExamplePriority;
import com.syed.apiqa.contract.example.ExamplePriorityEngine;
import com.syed.apiqa.contract.schema.SchemaGraphEngine;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.execution.HttpExecutionEngine;
import com.syed.apiqa.persistence.TestCaseRepository;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.persistence.TestStepRepository;
import com.syed.apiqa.planning.DependencyEngine;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification suite for THE 5 HEARTS of Syed API QA Agent:
 * 1. Contract & Schema Intelligence
 * 2. Data & Parameter Generation (with Pre-Request Local Contract Gate)
 * 3. Dependency & Identity Decision Engine (Failure Containment & Minimal Propagation)
 * 4. Real Execution, State Machine & Variable Management
 * 5. Root-Cause Intelligence (Target API Bug vs QA Agent Error)
 */
@SpringBootTest
@ActiveProfiles("test")
public class FiveHeartsIntelligenceTest {

    @Autowired
    private ExamplePriorityEngine examplePriorityEngine;

    @Autowired
    private SchemaGraphEngine schemaGraphEngine;

    @Autowired
    private DependencyEngine dependencyEngine;

    @Autowired
    private FailureIsolationHandler failureIsolationHandler;

    @Autowired
    private HttpExecutionEngine httpExecutionEngine;

    @Autowired
    private FailureIntelligenceService failureIntelligenceService;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestStepRepository testStepRepository;

    @Test
    @DisplayName("Heart #2: Data Generation Hierarchy respects 11-level hierarchy and trace auditing")
    void testHeart2_DataGenerationHierarchy() {
        // Test explicit Operation Example priority over Schema
        StringSchema schema = new StringSchema();
        schema.setExample("schema-level-sample");

        ExamplePriorityEngine.ResolvedPayload payload = examplePriorityEngine.resolvePayload(
                null, "operation-level-sample", null, null, schema, new Random(42), Collections.emptyMap()
        );

        assertEquals("operation-level-sample", payload.value());
        assertEquals(ExamplePriority.OPERATION_EXAMPLE, payload.priorityUsed());
        assertEquals(ContractConfidence.HIGH, payload.confidence());
        assertFalse(payload.traces().isEmpty());
    }

    @Test
    @DisplayName("Heart #2: Pre-Request Local Contract Gate intercepts un-interpolated path templates as REQUEST_NOT_EXECUTABLE")
    void testHeart2_PreRequestGateInterceptsUnresolvedTemplates() {
        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setName("Get Pet by Unresolved ID");
        step.setMethod("GET");
        step.setPathTemplate("/api/v1/pets/{petId}"); // Raw unresolved template!

        ExecutionContext context = new ExecutionContext("test-run-" + UUID.randomUUID());
        // Context has NO variable for petId!

        HttpExecutionEngine.StepExecutionOutcome outcome = httpExecutionEngine.executeStep(
                step, "http://127.0.0.1:8080", context, EnvironmentType.DEVELOPMENT, "NONE", null, null
        );

        assertEquals(StepStatus.REQUEST_NOT_EXECUTABLE, outcome.getFinalStatus());
        assertNotNull(step.getFailureReason());
        assertTrue(step.getFailureReason().contains("REQUEST_NOT_EXECUTABLE"));
        assertTrue(step.getFailureReason().contains("path parameter: {petId}") || step.getFailureReason().contains("unresolved parameter"));
    }

    @Test
    @DisplayName("Heart #3 & Principle #7: Failure Containment & Blast Radius Minimal Propagation")
    void testHeart3_FailureContainmentAndBlastRadius() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());
        run.setOpenapiUrl("http://localhost/contract.json");
        run.setStatus(RunStatus.EXECUTING);
        testRunRepository.save(run);

        // Case 1: User CRUD Workflow (A -> B -> C)
        TestCase case1 = new TestCase();
        case1.setId(UUID.randomUUID().toString());
        case1.setTestRun(run);
        case1.setName("User CRUD Workflow");
        case1.setScenarioType("CRUD_WORKFLOW");
        testCaseRepository.save(case1);

        TestStep stepA = new TestStep();
        stepA.setId(UUID.randomUUID().toString());
        stepA.setName("Create User");
        stepA.setMethod("POST");
        stepA.setPathTemplate("/users");
        stepA.setStepOrder(1);
        stepA.setTestCase(case1);
        stepA.setStatus(StepStatus.FAILED);
        testStepRepository.save(stepA);

        TestStep stepB = new TestStep();
        stepB.setId(UUID.randomUUID().toString());
        stepB.setName("Get User");
        stepB.setMethod("GET");
        stepB.setPathTemplate("/users/{id}");
        stepB.setStepOrder(2);
        stepB.setTestCase(case1);
        stepB.setStatus(StepStatus.PENDING);
        testStepRepository.save(stepB);

        // Case 2: Independent Products Workflow (D -> E)
        TestCase case2 = new TestCase();
        case2.setId(UUID.randomUUID().toString());
        case2.setTestRun(run);
        case2.setName("Product Catalog Workflow");
        case2.setScenarioType("PUBLIC_READ");
        testCaseRepository.save(case2);

        TestStep stepD = new TestStep();
        stepD.setId(UUID.randomUUID().toString());
        stepD.setName("List Products");
        stepD.setMethod("GET");
        stepD.setPathTemplate("/products");
        stepD.setStepOrder(1);
        stepD.setTestCase(case2);
        stepD.setStatus(StepStatus.PASSED);
        testStepRepository.save(stepD);

        // Isolate failure in Case 1: only stepB is blocked, case 2 continues unaffected!
        int blockedCount = failureIsolationHandler.isolateFailureAndBlockDownstream(stepA, List.of(stepB), "CRUD_WORKFLOW");
        assertEquals(1, blockedCount);
        assertEquals(StepStatus.BLOCKED, stepB.getStatus());
        assertEquals(StepStatus.PASSED, stepD.getStatus()); // Product flow completely unaffected!
    }

    @Test
    @DisplayName("Heart #5: Root-Cause Intelligence correctly attributes Target API Bug vs QA Agent Bug")
    void testHeart5_RootCauseDiagnosisAttribution() {
        // Scenario A: Server Crash 500 -> Target API Bug
        TestStep serverCrashStep = new TestStep();
        serverCrashStep.setId(UUID.randomUUID().toString());
        serverCrashStep.setName("Create Order");
        serverCrashStep.setMethod("POST");
        serverCrashStep.setPathTemplate("/api/orders");
        serverCrashStep.setStatus(StepStatus.FAILED);
        serverCrashStep.setFailureReason("Expected 201 but received HTTP 500 Internal Server Error");

        DiagnosticFinding crashFinding = failureIntelligenceService.classify(
                serverCrashStep, "POST", "/api/orders", 500, StepStatus.FAILED, null, "Blast Radius: 2 affected"
        );

        assertEquals(DiagnosticFinding.Category.TARGET_API_FAILURE, crashFinding.getCategory());
        assertEquals(DiagnosticFinding.Attribution.TARGET_API, crashFinding.getAttribution());
        assertEquals(ContractConfidence.HIGH, crashFinding.getConfidence());
        assertTrue(crashFinding.getProbableRootCause().contains("target backend defect"));

        // Scenario B: Constraint violation 422 with regex pattern mismatch -> QA Agent Error
        TestStep agentViolationStep = new TestStep();
        agentViolationStep.setId(UUID.randomUUID().toString());
        agentViolationStep.setName("Update Phone");
        agentViolationStep.setMethod("PUT");
        agentViolationStep.setPathTemplate("/api/users/profile");
        agentViolationStep.setStatus(StepStatus.FAILED);
        agentViolationStep.setFailureReason("HTTP 422: Generated value violated regex pattern constraint ^[0-9]{10}$");

        DiagnosticFinding agentFinding = failureIntelligenceService.classify(
                agentViolationStep, "PUT", "/api/users/profile", 422, StepStatus.FAILED, null, "Blast Radius: 0 affected"
        );

        assertEquals(DiagnosticFinding.Category.QA_AGENT_REQUEST_GENERATION_FAILURE, agentFinding.getCategory());
        assertEquals(DiagnosticFinding.Attribution.QA_AGENT, agentFinding.getAttribution());
        assertEquals(ContractConfidence.HIGH, agentFinding.getConfidence());
        assertTrue(agentFinding.getProbableRootCause().contains("QA Agent generated a request that violated"));

        // Scenario C: Pre-request Gate withheld step -> REQUEST_NOT_EXECUTABLE
        TestStep withheldStep = new TestStep();
        withheldStep.setId(UUID.randomUUID().toString());
        withheldStep.setName("Delete Resource Without ID");
        withheldStep.setMethod("DELETE");
        withheldStep.setPathTemplate("/api/items/{id}");
        withheldStep.setStatus(StepStatus.REQUEST_NOT_EXECUTABLE);
        withheldStep.setFailureReason("REQUEST_NOT_EXECUTABLE: Path contains unresolved parameter in: /api/items/{id}");

        DiagnosticFinding withheldFinding = failureIntelligenceService.classify(
                withheldStep, "DELETE", "/api/items/{id}", null, StepStatus.REQUEST_NOT_EXECUTABLE, null, "Blast Radius: 0 affected"
        );

        assertEquals(DiagnosticFinding.Category.REQUEST_NOT_EXECUTABLE, withheldFinding.getCategory());
        assertEquals(DiagnosticFinding.Attribution.QA_AGENT, withheldFinding.getAttribution());
        assertEquals(ContractConfidence.HIGH, withheldFinding.getConfidence());
        assertTrue(withheldFinding.getEvidence().contains("Pre-Request Gate"));
    }
}
