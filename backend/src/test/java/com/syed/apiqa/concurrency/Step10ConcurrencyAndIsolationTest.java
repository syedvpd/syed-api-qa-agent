package com.syed.apiqa.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.generation.DeterministicDataGenerator;
import com.syed.apiqa.generation.NegativeDataGenerator;
import com.syed.apiqa.planning.DependencyEngine;
import com.syed.apiqa.planning.TestPlanService;
import com.syed.apiqa.planning.dag.DagExecutionScheduler;
import com.syed.apiqa.planning.dag.DagNode;
import com.syed.apiqa.planning.dag.DependencyGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Step10ConcurrencyAndIsolationTest {

    @Test
    @DisplayName("Verify 10 Concurrent SaaS Runs have strict Variable and Context Isolation")
    void testTenConcurrentRunsVariableIsolation() throws Exception {
        int runCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(runCount);
        CountDownLatch latch = new CountDownLatch(runCount);
        Map<String, String> resolvedValuesByRun = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= runCount; i++) {
            final int runIdx = i;
            futures.add(executor.submit(() -> {
                try {
                    String runId = "run_tenant_" + runIdx;
                    ExecutionContext context = new ExecutionContext(runId);

                    // Each tenant sets a unique tenant variable
                    String tenantVarValue = "tenant_secret_value_" + runIdx;
                    context.setVariable("tenant.id", tenantVarValue);

                    // Wait for all 10 threads to start simultaneously
                    latch.countDown();
                    latch.await(5, TimeUnit.SECONDS);

                    // Resolve mustache placeholder
                    ExecutionContext.ResolutionResult res = context.resolve("{{tenant.id}}");
                    resolvedValuesByRun.put(runId, res.getResolvedContent());

                    // Verify cross-tenant isolation: {{tenant.id}} MUST NOT equal any other tenant's secret
                    for (int j = 1; j <= runCount; j++) {
                        if (j != runIdx) {
                            assertNotEquals("tenant_secret_value_" + j, res.getResolvedContent(),
                                    "Variable isolation defect: Run " + runId + " leaked variable from tenant " + j);
                        }
                    }
                } catch (Exception e) {
                    fail("Run " + runIdx + " failed: " + e.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(10, resolvedValuesByRun.size());
        for (int i = 1; i <= runCount; i++) {
            assertEquals("tenant_secret_value_" + i, resolvedValuesByRun.get("run_tenant_" + i));
        }
    }

    @Test
    @DisplayName("Verify 500-Node Synthetic DAG Topological Execution under Concurrency")
    void testFiveHundredNodeDagStress() throws Exception {
        int nodeCount = 500;
        TestRun run = new TestRun("run_dag_500", "https://jsonplaceholder.typicode.com", EnvironmentType.STAGING);
        List<TestCase> cases = new ArrayList<>();

        // Create 500 test cases with sequential dependency chains (Node N depends on Node N-1)
        for (int i = 1; i <= nodeCount; i++) {
            TestCase tc = new TestCase();
            tc.setId("tc_node_" + i);
            tc.setTestRun(run);
            tc.setName("Test Case " + i);
            tc.setScenarioType("POSITIVE_SMOKE");
            tc.setExecutionOrder(i);
            cases.add(tc);
        }

        List<Dependency> dependencies = new ArrayList<>();
        for (int i = 2; i <= nodeCount; i++) {
            Dependency dep = new Dependency();
            dep.setId("dep_" + i);
            dep.setTestRun(run);

            ApiEndpoint producer = new ApiEndpoint();
            producer.setId("tc_node_" + (i - 1));
            dep.setProducerEndpoint(producer);

            ApiEndpoint consumer = new ApiEndpoint();
            consumer.setId("tc_node_" + i);
            dep.setConsumerEndpoint(consumer);

            dep.setParameterName("id");
            dep.setSourceField("id");
            dependencies.add(dep);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        DependencyEngine dependencyEngine = new DependencyEngine(objectMapper);
        DependencyGraph graph = dependencyEngine.buildDependencyGraph(run, cases, dependencies);

        assertEquals(nodeCount, graph.getNodeCount(), "500-Node DAG graph node count mismatch");

        // Execute topological scheduler with 8 worker threads
        ExecutionContext context = new ExecutionContext(run.getId());
        DagExecutionScheduler scheduler = new DagExecutionScheduler(graph, 8);
        AtomicInteger executedCounter = new AtomicInteger(0);

        scheduler.execute(context, (node, ctx) -> {
            executedCounter.incrementAndGet();
            return DagNode.NodeStatus.PASSED;
        });

        assertEquals(nodeCount, executedCounter.get(), "500-Node DAG failed to execute all nodes topologically");
    }

    @Test
    @DisplayName("Verify 1000-Step Synthetic Test Plan Formulation and Accounting Reconciliation")
    void testThousandStepTestPlanAccounting() {
        int stepCount = 1000;
        TestRun run = new TestRun("run_1000_steps", "https://jsonplaceholder.typicode.com", EnvironmentType.STAGING);
        
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (int i = 1; i <= stepCount; i++) {
            ApiEndpoint ep = new ApiEndpoint();
            ep.setId("ep_" + i);
            ep.setMethod("GET");
            ep.setPath("/endpoint_" + i);
            ep.setOperationId("op_" + i);
            endpoints.add(ep);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        DependencyEngine dependencyEngine = new DependencyEngine(objectMapper);
        DeterministicDataGenerator dataGenerator = new DeterministicDataGenerator(objectMapper);
        NegativeDataGenerator negativeDataGenerator = new NegativeDataGenerator(dataGenerator);

        TestPlanService testPlanService = new TestPlanService(dataGenerator, negativeDataGenerator, dependencyEngine, objectMapper);
        TestPlanService.PlanResult planResult = testPlanService.buildTestPlan(run, endpoints, Collections.emptyList(), null);

        int totalStepsCount = 0;
        for (TestCase tc : planResult.getTestCases()) {
            List<TestStep> steps = planResult.getStepsByCaseId().get(tc.getId());
            if (steps != null) {
                totalStepsCount += steps.size();
            }
        }

        assertTrue(totalStepsCount >= stepCount, "Test plan formulation truncated 1000-step workload");
        assertEquals(planResult.getTestCases().size(), planResult.getStepsByCaseId().size());
    }

    @Test
    @DisplayName("Verify Authentication Session Isolation across Tenants")
    void testAuthSessionTenantIsolation() {
        ExecutionContext ctx1 = new ExecutionContext("run_auth_1");
        ExecutionContext ctx2 = new ExecutionContext("run_auth_2");

        IdentitySession s1 = new IdentitySession("ident_1", "Developer 1");
        s1.setAccessToken("token_dev_1_secret");
        ctx1.registerSession(s1);

        IdentitySession s2 = new IdentitySession("ident_2", "Developer 2");
        s2.setAccessToken("token_dev_2_secret");
        ctx2.registerSession(s2);

        assertNotNull(ctx1.getSession("ident_1"));
        assertNull(ctx1.getSession("ident_2"), "Tenant 1 context leaked Developer 2 session");

        assertNotNull(ctx2.getSession("ident_2"));
        assertNull(ctx2.getSession("ident_1"), "Tenant 2 context leaked Developer 1 session");
    }
}
