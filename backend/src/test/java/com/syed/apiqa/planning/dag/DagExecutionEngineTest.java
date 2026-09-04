package com.syed.apiqa.planning.dag;

import com.syed.apiqa.domain.ConfidenceLevel;
import com.syed.apiqa.execution.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class DagExecutionEngineTest {

    @Test
    @DisplayName("Phase 3.1: Linear Chain DAG (A -> B -> C -> D)")
    public void testLinearChainDag() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        DagNode nodeA = new DagNode("A", "Step A", null);
        DagNode nodeB = new DagNode("B", "Step B", null);
        DagNode nodeC = new DagNode("C", "Step C", null);
        DagNode nodeD = new DagNode("D", "Step D", null);

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addNode(nodeC);
        graph.addNode(nodeD);

        graph.addEdge(new DagEdge("A", "B", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, "A to B"));
        graph.addEdge(new DagEdge("B", "C", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, "B to C"));
        graph.addEdge(new DagEdge("C", "D", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, "C to D"));

        List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
        ExecutionContext context = new ExecutionContext("run-linear");

        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 4);
        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            executionLog.add(node.getId());
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(4, summary.getTotalNodes());
        assertEquals(4, summary.getPassedCount());
        assertEquals(0, summary.getFailedCount());
        assertEquals(0, summary.getBlockedCount());
        assertTrue(summary.isAllPassed());
        assertTrue(summary.isAccounted());

        // Verify strictly sequential order
        assertEquals(List.of("A", "B", "C", "D"), executionLog);
    }

    @Test
    @DisplayName("Phase 3.2: Branching and Diamond Join DAG (A -> B, A -> C, B -> D, C -> D)")
    public void testDiamondJoinDag() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        DagNode nodeA = new DagNode("A", "Producer A", null);
        DagNode nodeB = new DagNode("B", "Branch B", null);
        DagNode nodeC = new DagNode("C", "Branch C", null);
        DagNode nodeD = new DagNode("D", "Join D", null);

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addNode(nodeC);
        graph.addNode(nodeD);

        graph.addEdge(new DagEdge("A", "B", "a_id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("A", "C", "a_id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("B", "D", "b_id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("C", "D", "c_id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));

        List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
        ExecutionContext context = new ExecutionContext("run-diamond");

        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 4);
        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            executionLog.add(node.getId());
            Thread.sleep(10);
            return DagNode.NodeStatus.PASSED;
        });

        assertTrue(summary.isAllPassed());
        assertEquals(4, summary.getTotalNodes());

        // A must be first, D must be last
        assertEquals("A", executionLog.get(0));
        assertEquals("D", executionLog.get(3));
        assertTrue(executionLog.contains("B"));
        assertTrue(executionLog.contains("C"));
    }

    @Test
    @DisplayName("Phase 6: Failure Propagation & Isolation (A fails -> B, C blocked, X and Y continue)")
    public void testFailurePropagationAndIsolation() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        // Dependent branch: A -> B -> C
        DagNode nodeA = new DagNode("A", "Producer A", null);
        DagNode nodeB = new DagNode("B", "Consumer B", null);
        DagNode nodeC = new DagNode("C", "Consumer C", null);

        // Independent branch: X -> Y
        DagNode nodeX = new DagNode("X", "Independent X", null);
        DagNode nodeY = new DagNode("Y", "Independent Y", null);

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addNode(nodeC);
        graph.addNode(nodeX);
        graph.addNode(nodeY);

        graph.addEdge(new DagEdge("A", "B", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("B", "C", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("X", "Y", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));

        ExecutionContext context = new ExecutionContext("run-failure-iso");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 4);

        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            if ("A".equals(node.getId())) {
                return DagNode.NodeStatus.FAILED; // Injected failure at A
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(5, summary.getTotalNodes());
        assertEquals(1, summary.getFailedCount()); // A failed
        assertEquals(2, summary.getBlockedCount()); // B and C blocked
        assertEquals(2, summary.getPassedCount()); // X and Y passed
        assertTrue(summary.isAccounted());

        assertEquals(DagNode.NodeStatus.FAILED, graph.getNode("A").getStatus());
        assertEquals(DagNode.NodeStatus.BLOCKED, graph.getNode("B").getStatus());
        assertEquals(DagNode.NodeStatus.BLOCKED, graph.getNode("C").getStatus());
        assertEquals(DagNode.NodeStatus.PASSED, graph.getNode("X").getStatus());
        assertEquals(DagNode.NodeStatus.PASSED, graph.getNode("Y").getStatus());
    }

    @Test
    @DisplayName("Phase 3.3: Cycle Detection and Automatic Breaking (A -> B -> C -> A)")
    public void testCycleDetectionAndBreaking() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        DagNode nodeA = new DagNode("A", "Node A", null);
        DagNode nodeB = new DagNode("B", "Node B", null);
        DagNode nodeC = new DagNode("C", "Node C", null);

        graph.addNode(nodeA);
        graph.addNode(nodeB);
        graph.addNode(nodeC);

        graph.addEdge(new DagEdge("A", "B", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("B", "C", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("C", "A", "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.LOW, "")); // Back-edge

        int broken = graph.detectAndBreakCycles();
        assertEquals(1, broken, "Cycle should be detected and broken");

        ExecutionContext context = new ExecutionContext("run-cycle");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 2);
        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> DagNode.NodeStatus.PASSED);

        assertTrue(summary.isAllPassed());
        assertEquals(3, summary.getPassedCount());
    }

    @Test
    @DisplayName("Phase 5 & 15: Large Synthetic DAG Scaling (10, 25, 50, 100, 250, 500 Nodes)")
    public void testLargeScaleSyntheticDags() throws Exception {
        int[] scaleTargets = {10, 25, 50, 100, 250, 500};

        for (int nodeCount : scaleTargets) {
            DependencyGraph graph = new DependencyGraph();

            for (int i = 0; i < nodeCount; i++) {
                graph.addNode(new DagNode("N_" + i, "Node " + i, null));
            }

            // Create a rich mixed graph: 50% linear chains, 30% diamond joins, 20% independent
            for (int i = 0; i < nodeCount - 1; i++) {
                if (i % 5 != 0) { // Keep some independent
                    graph.addEdge(new DagEdge("N_" + i, "N_" + (i + 1), "id", "id",
                            DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, "Edge"));
                }
                if (i + 2 < nodeCount && i % 3 == 0) {
                    graph.addEdge(new DagEdge("N_" + i, "N_" + (i + 2), "id", "id",
                            DagEdge.ParameterLocation.PATH, ConfidenceLevel.MEDIUM, "Skip Edge"));
                }
            }

            ExecutionContext context = new ExecutionContext("run-scale-" + nodeCount);
            DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 16);

            long t0 = System.currentTimeMillis();
            DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> DagNode.NodeStatus.PASSED);
            long elapsed = System.currentTimeMillis() - t0;

            assertEquals(nodeCount, summary.getTotalNodes());
            assertEquals(nodeCount, summary.getPassedCount());
            assertEquals(0, summary.getFailedCount());
            assertEquals(0, summary.getBlockedCount());
            assertTrue(summary.isAccounted());
            assertTrue(elapsed < 5000, "Large graph (" + nodeCount + " nodes) executed in " + elapsed + "ms");
        }
    }

    @Test
    @DisplayName("Phase 10: High Concurrency Safety (50 Independent Dependent Branches)")
    public void testHighConcurrencyIndependentBranches() throws Exception {
        int branchCount = 50;
        DependencyGraph graph = new DependencyGraph();

        // 50 branches: P_i -> C_i
        for (int i = 0; i < branchCount; i++) {
            DagNode producer = new DagNode("P_" + i, "Producer " + i, null);
            DagNode consumer = new DagNode("C_" + i, "Consumer " + i, null);
            graph.addNode(producer);
            graph.addNode(consumer);
            graph.addEdge(new DagEdge("P_" + i, "C_" + i, "id", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        }

        Set<String> finishedProducers = ConcurrentHashMap.newKeySet();
        AtomicInteger violationCount = new AtomicInteger(0);

        ExecutionContext context = new ExecutionContext("run-concurrency");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 16);

        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            String id = node.getId();
            if (id.startsWith("P_")) {
                Thread.sleep(5);
                finishedProducers.add(id);
            } else if (id.startsWith("C_")) {
                String expectedProducer = "P_" + id.substring(2);
                if (!finishedProducers.contains(expectedProducer)) {
                    violationCount.incrementAndGet();
                }
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(0, violationCount.get(), "No consumer should ever execute before its producer");
        assertEquals(branchCount * 2, summary.getTotalNodes());
        assertTrue(summary.isAllPassed());
    }

    @Test
    @DisplayName("Phase 4: Multi-Source Variable Dependency Resolution")
    public void testMultiSourceVariableDependency() throws Exception {
        DependencyGraph graph = new DependencyGraph();
        DagNode authProducer = new DagNode("Auth", "POST /auth/login", null);
        DagNode userProducer = new DagNode("User", "GET /auth/me", null);
        DagNode pathConsumer = new DagNode("PathConsumer", "GET /users/{userId}", null);
        DagNode queryConsumer = new DagNode("QueryConsumer", "GET /orders?userId={{user.id}}", null);
        DagNode bodyConsumer = new DagNode("BodyConsumer", "POST /pets", null);

        graph.addNode(authProducer);
        graph.addNode(userProducer);
        graph.addNode(pathConsumer);
        graph.addNode(queryConsumer);
        graph.addNode(bodyConsumer);

        graph.addEdge(new DagEdge("Auth", "User", "token", "access_token", DagEdge.ParameterLocation.AUTH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("User", "PathConsumer", "userId", "id", DagEdge.ParameterLocation.PATH, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("User", "QueryConsumer", "userId", "id", DagEdge.ParameterLocation.QUERY, ConfidenceLevel.HIGH, ""));
        graph.addEdge(new DagEdge("User", "BodyConsumer", "ownerId", "id", DagEdge.ParameterLocation.BODY, ConfidenceLevel.HIGH, ""));

        ExecutionContext context = new ExecutionContext("run-multi-source");
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 4);

        DagExecutionScheduler.DagExecutionSummary summary = scheduler.execute(context, (node, ctx) -> {
            if ("Auth".equals(node.getId())) {
                ctx.setVariable("auth.token", "jwt_token_123");
            } else if ("User".equals(node.getId())) {
                ctx.setVariable("user.id", "usr_999");
                ctx.setVariable("userId", "usr_999");
            } else if ("PathConsumer".equals(node.getId())) {
                assertEquals("usr_999", ctx.getVariable("userId"));
            } else if ("QueryConsumer".equals(node.getId())) {
                assertEquals("usr_999", ctx.getVariable("user.id"));
            } else if ("BodyConsumer".equals(node.getId())) {
                assertEquals("usr_999", ctx.getVariable("user.id"));
            }
            return DagNode.NodeStatus.PASSED;
        });

        assertTrue(summary.isAllPassed());
        assertEquals(5, summary.getTotalNodes());
    }
}
