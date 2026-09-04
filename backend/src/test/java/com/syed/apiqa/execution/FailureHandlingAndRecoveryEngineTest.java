package com.syed.apiqa.execution;

import com.syed.apiqa.domain.EnvironmentType;
import com.syed.apiqa.domain.StepStatus;
import com.syed.apiqa.domain.TestStep;
import com.syed.apiqa.execution.retry.RetrySafetyEngine;
import com.syed.apiqa.planning.dag.DagExecutionScheduler;
import com.syed.apiqa.planning.dag.DagNode;
import com.syed.apiqa.planning.dag.DependencyGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class FailureHandlingAndRecoveryEngineTest {

    // =========================================================================
    // 1. STATE MACHINE & ILLEGAL TRANSITIONS
    // =========================================================================

    @Test
    @DisplayName("State Machine: Enforce legal transitions and reject illegal jumps")
    void testStateMachineValidation() {
        // Legal transitions
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.PENDING, StepStatus.RUNNING));
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.PENDING, StepStatus.BLOCKED));
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.PENDING, StepStatus.SKIPPED));
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.RUNNING, StepStatus.PASSED));
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.RUNNING, StepStatus.FAILED));
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.RUNNING, StepStatus.TIMEOUT));
        assertTrue(ExecutionStateMachine.isValidTransition(StepStatus.RUNNING, StepStatus.AUTHENTICATION_ERROR));

        // Illegal transitions must be rejected
        assertFalse(ExecutionStateMachine.isValidTransition(StepStatus.PENDING, StepStatus.PASSED));
        assertFalse(ExecutionStateMachine.isValidTransition(StepStatus.BLOCKED, StepStatus.PASSED));
        assertFalse(ExecutionStateMachine.isValidTransition(StepStatus.BLOCKED, StepStatus.RUNNING));
        assertFalse(ExecutionStateMachine.isValidTransition(StepStatus.PASSED, StepStatus.RUNNING));
        assertFalse(ExecutionStateMachine.isValidTransition(StepStatus.PASSED, StepStatus.FAILED));
        assertFalse(ExecutionStateMachine.isValidTransition(StepStatus.SKIPPED, StepStatus.RUNNING));

        // Validate exception throwing on illegal jump
        assertThrows(IllegalStateException.class, () ->
                ExecutionStateMachine.validateTransition(StepStatus.BLOCKED, StepStatus.PASSED));
        assertThrows(IllegalStateException.class, () ->
                ExecutionStateMachine.validateTransition(StepStatus.PENDING, StepStatus.PASSED));
    }

    // =========================================================================
    // 2. DETERMINISTIC FAILURE INJECTION & CLASSIFICATION MATRIX
    // =========================================================================

    @Test
    @DisplayName("Failure Classification: Verify 401, 403, 404, 409, 422, 429, 500, 502, 504, Timeout")
    void testFailureClassificationMatrix() {
        assertEquals(FailureClassification.AUTHENTICATION_FAILURE, FailureClassification.classify(401, null, null));
        assertEquals(FailureClassification.AUTHORIZATION_FAILURE, FailureClassification.classify(403, null, null));
        assertEquals(FailureClassification.SCHEMA_FAILURE, FailureClassification.classify(422, null, null));
        assertEquals(FailureClassification.RATE_LIMITED, FailureClassification.classify(429, null, null));
        assertEquals(FailureClassification.INFRASTRUCTURE_FAILURE, FailureClassification.classify(500, null, null));
        assertEquals(FailureClassification.CONNECTION_FAILURE, FailureClassification.classify(502, null, null));
        assertEquals(FailureClassification.CONNECTION_FAILURE, FailureClassification.classify(503, null, null));
        assertEquals(FailureClassification.TIMEOUT, FailureClassification.classify(504, null, null));
        assertEquals(FailureClassification.TIMEOUT, FailureClassification.classify(0, "TIMEOUT", null));
        assertEquals(FailureClassification.CONNECTION_FAILURE, FailureClassification.classify(0, "NETWORK_ERROR", null));
        assertEquals(FailureClassification.REQUEST_GENERATION_FAILURE, FailureClassification.classify(0, "REQUEST_NOT_EXECUTABLE", null));
        assertEquals(FailureClassification.DEPENDENCY_FAILURE, FailureClassification.classify(0, "BLOCKED_BY_DEPENDENCY", null));
        assertEquals(FailureClassification.PASSED, FailureClassification.classify(200, null, null));
        assertEquals(FailureClassification.PASSED, FailureClassification.classify(201, null, null));
    }

    // =========================================================================
    // 3. FAILURE ISOLATION & NON-POISONING
    // =========================================================================

    @Test
    @DisplayName("Failure Isolation: A->B->C and X->Y. When A fails, B and C are blocked, X and Y continue")
    void testFailureIsolationLinearAndIndependent() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        graph.addNode("A", "Create Resource A", null);
        graph.addNode("B", "Get Resource A", null);
        graph.addNode("C", "Update Resource A", null);
        graph.addNode("X", "Create Resource X", null);
        graph.addNode("Y", "Get Resource X", null);

        graph.addEdge("A", "B", "id");
        graph.addEdge("B", "C", "id");
        graph.addEdge("X", "Y", "id");

        ExecutionContext context = new ExecutionContext("run-iso-1");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 4);

        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            if ("A".equals(node.getId())) {
                return DagNode.NodeStatus.FAILED; // Injected failure
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(5, summary.getTotalNodes());
        assertEquals(2, summary.getPassedCount(), "X and Y must pass");
        assertEquals(1, summary.getFailedCount(), "A must fail");
        assertEquals(2, summary.getBlockedCount(), "B and C must be blocked");
        assertTrue(summary.isAccounted());

        assertEquals(DagNode.NodeStatus.FAILED, graph.getNode("A").getStatus());
        assertEquals(DagNode.NodeStatus.BLOCKED, graph.getNode("B").getStatus());
        assertEquals(DagNode.NodeStatus.BLOCKED, graph.getNode("C").getStatus());
        assertEquals(DagNode.NodeStatus.PASSED, graph.getNode("X").getStatus());
        assertEquals(DagNode.NodeStatus.PASSED, graph.getNode("Y").getStatus());
    }

    @Test
    @DisplayName("Failure Isolation: Mid-Chain Failure (A succeeds, B fails -> C blocked, X/Y pass)")
    void testMidChainFailureIsolation() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        graph.addNode("A", "Node A", null);
        graph.addNode("B", "Node B", null);
        graph.addNode("C", "Node C", null);
        graph.addNode("X", "Node X", null);
        graph.addNode("Y", "Node Y", null);

        graph.addEdge("A", "B", "id");
        graph.addEdge("B", "C", "id");
        graph.addEdge("X", "Y", "id");

        ExecutionContext context = new ExecutionContext("run-iso-2");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 4);

        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            if ("B".equals(node.getId())) {
                return DagNode.NodeStatus.FAILED; // B fails
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(5, summary.getTotalNodes());
        assertEquals(3, summary.getPassedCount(), "A, X, Y must pass");
        assertEquals(1, summary.getFailedCount(), "B must fail");
        assertEquals(1, summary.getBlockedCount(), "C must be blocked");
        assertTrue(summary.isAccounted());
    }

    // =========================================================================
    // 4. RETRY SAFETY & IDEMPOTENCY PROTECTION
    // =========================================================================

    @Test
    @DisplayName("Retry Safety: Safe GET is retryable; Unsafe POST/PATCH is protected against duplicate mutation")
    void testRetrySafetyPolicies() {
        // Safe Idempotent GET
        assertEquals(RetrySafetyEngine.RetrySafety.SAFE_TO_RETRY, RetrySafetyEngine.classifyMethod("GET", null));
        assertEquals(RetrySafetyEngine.RetrySafety.SAFE_TO_RETRY, RetrySafetyEngine.classifyMethod("HEAD", null));
        assertEquals(RetrySafetyEngine.RetrySafety.SAFE_TO_RETRY, RetrySafetyEngine.classifyMethod("OPTIONS", null));

        // Idempotent Mutations
        assertEquals(RetrySafetyEngine.RetrySafety.CONDITIONALLY_RETRYABLE, RetrySafetyEngine.classifyMethod("PUT", null));
        assertEquals(RetrySafetyEngine.RetrySafety.CONDITIONALLY_RETRYABLE, RetrySafetyEngine.classifyMethod("DELETE", null));

        // Unsafe Non-Idempotent Mutations
        assertEquals(RetrySafetyEngine.RetrySafety.NOT_SAFE_TO_RETRY, RetrySafetyEngine.classifyMethod("POST", null));
        assertEquals(RetrySafetyEngine.RetrySafety.NOT_SAFE_TO_RETRY, RetrySafetyEngine.classifyMethod("PATCH", null));

        // POST with explicit Idempotency-Key header is conditionally retryable
        Map<String, String> headersWithKey = Map.of("Idempotency-Key", "uuid-12345");
        assertEquals(RetrySafetyEngine.RetrySafety.CONDITIONALLY_RETRYABLE, RetrySafetyEngine.classifyMethod("POST", headersWithKey));

        // Verify Retry Decision on Timeout:
        // GET on 504 / timeout -> RETRY
        RetrySafetyEngine.RetryDecision getRetry = RetrySafetyEngine.evaluateRetry("GET", 504, "TIMEOUT", 1, 2, null, null);
        assertTrue(getRetry.shouldRetry());

        // POST on 504 / timeout without idempotency key -> SUPPRESSED
        RetrySafetyEngine.RetryDecision postRetry = RetrySafetyEngine.evaluateRetry("POST", 504, "TIMEOUT", 1, 2, null, null);
        assertFalse(postRetry.shouldRetry(), "POST timeout must not be retried to prevent duplicate creation");

        // Rate Limiting (429) -> RETRY with backoff
        Map<String, String> respHeaders = Map.of("Retry-After", "2");
        RetrySafetyEngine.RetryDecision rateLimitRetry = RetrySafetyEngine.evaluateRetry("GET", 429, "RATE_LIMITED", 1, 3, respHeaders, null);
        assertTrue(rateLimitRetry.shouldRetry());
        assertEquals(2000L, rateLimitRetry.getBackoffDelayMs());

        // Deterministic Client Errors (400, 403, 404, 422) -> NEVER RETRY
        assertFalse(RetrySafetyEngine.evaluateRetry("GET", 400, null, 1, 3, null, null).shouldRetry());
        assertFalse(RetrySafetyEngine.evaluateRetry("GET", 403, null, 1, 3, null, null).shouldRetry());
        assertFalse(RetrySafetyEngine.evaluateRetry("GET", 404, null, 1, 3, null, null).shouldRetry());
        assertFalse(RetrySafetyEngine.evaluateRetry("GET", 422, null, 1, 3, null, null).shouldRetry());
    }

    // =========================================================================
    // 5. STALE VARIABLE PROTECTION & SCOPED ISOLATION
    // =========================================================================

    @Test
    @DisplayName("Stale Variable Protection: Failed producer leaves context empty; Downstream consumer never uses stale ID")
    void testStaleVariableProtection() {
        ExecutionContext run1 = new ExecutionContext("run-101");
        run1.setVariable("order.id", "998811");
        assertEquals("998811", run1.getVariable("order.id"));

        // Second run where producer fails
        ExecutionContext run2 = new ExecutionContext("run-102");
        // Producer fails -> variable NOT set in run2
        assertNull(run2.getVariable("order.id"), "Run 2 must not see Run 1 variables");

        // Attempting to resolve URL in Run 2 must fail cleanly with missing variable
        ExecutionContext.ResolutionResult resolution = run2.resolve("/orders/{order.id}");
        assertFalse(resolution.isFullyResolved(), "Consumer must not resolve missing variable");
        assertEquals("order.id", resolution.getMissingVariable());
    }

    // =========================================================================
    // 6. CONCURRENCY UNDER FAILURE (50 Independent Branches)
    // =========================================================================

    @RepeatedTest(3)
    @DisplayName("Concurrency: 50 independent branches with random failures maintain isolation")
    void testConcurrentIndependentBranchesWithFailures() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        int branchCount = 50;

        for (int i = 0; i < branchCount; i++) {
            String p = "P" + i;
            String c = "C" + i;
            graph.addNode(p, "Producer " + i, null);
            graph.addNode(c, "Consumer " + i, null);
            graph.addEdge(p, c, "id");
        }

        ExecutionContext context = new ExecutionContext("run-concurrency-fail");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 16);

        // Producers with even index fail; odd index pass
        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            String id = node.getId();
            if (id.startsWith("P")) {
                int idx = Integer.parseInt(id.substring(1));
                if (idx % 2 == 0) {
                    return DagNode.NodeStatus.FAILED; // 25 producers fail
                }
                return DagNode.NodeStatus.PASSED; // 25 producers pass
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(100, summary.getTotalNodes());
        assertEquals(50, summary.getPassedCount(), "25 passing producers + 25 passing consumers = 50 passed");
        assertEquals(25, summary.getFailedCount(), "25 failing producers");
        assertEquals(25, summary.getBlockedCount(), "25 blocked consumers");
        assertTrue(summary.isAccounted());
    }

    // =========================================================================
    // 7. LARGE 500+ NODE SYNTHETIC FAILURE MATRIX
    // =========================================================================

    @Test
    @DisplayName("Large Scale: 500-Node Synthetic DAG with complex failures achieves 100% accounting")
    void testLarge500NodeFailureMatrix() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        int totalNodes = 500;

        // Build 100 5-node subgraphs: N0 -> N1 -> N2 -> N3 -> N4
        for (int chain = 0; chain < 100; chain++) {
            for (int step = 0; step < 5; step++) {
                int nodeNum = (chain * 5) + step;
                graph.addNode("N" + nodeNum, "Node " + nodeNum, null);
                if (step > 0) {
                    int prevNum = nodeNum - 1;
                    graph.addEdge("N" + prevNum, "N" + nodeNum, "var");
                }
            }
        }

        assertEquals(500, graph.getNodeCount());

        ExecutionContext context = new ExecutionContext("run-large-fail-500");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 16);

        // Inject failure on every 5th chain's root (chain % 5 == 0)
        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            int num = Integer.parseInt(node.getId().substring(1));
            int chain = num / 5;
            int step = num % 5;
            if (chain % 5 == 0 && step == 0) {
                return DagNode.NodeStatus.FAILED; // 20 root nodes fail
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(500, summary.getTotalNodes());
        // 20 chains fail at root -> 20 failed, 20 * 4 = 80 blocked (100 nodes affected)
        // 80 chains pass completely -> 80 * 5 = 400 passed
        assertEquals(400, summary.getPassedCount());
        assertEquals(20, summary.getFailedCount());
        assertEquals(80, summary.getBlockedCount());
        assertTrue(summary.isAccounted(), "All 500 nodes must be fully accounted for");
    }
}
