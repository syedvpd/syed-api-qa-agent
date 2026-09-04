package com.syed.apiqa.planning.dag;

import com.syed.apiqa.execution.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe DAG Execution Scheduler.
 * Coordinates topological execution, asynchronous ready-queue dispatching,
 * bounded concurrency, failure propagation, and strict operation accounting.
 */
public class DagExecutionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DagExecutionScheduler.class);

    @FunctionalInterface
    public interface NodeExecutor {
        DagNode.NodeStatus execute(DagNode node, ExecutionContext context) throws Exception;
    }

    public static class DagExecutionSummary {
        private final int totalNodes;
        private final int passedCount;
        private final int failedCount;
        private final int blockedCount;
        private final int skippedCount;
        private final long durationMs;
        private final List<String> executionOrder;

        public DagExecutionSummary(int totalNodes, int passedCount, int failedCount, int blockedCount,
                                   int skippedCount, long durationMs, List<String> executionOrder) {
            this.totalNodes = totalNodes;
            this.passedCount = passedCount;
            this.failedCount = failedCount;
            this.blockedCount = blockedCount;
            this.skippedCount = skippedCount;
            this.durationMs = durationMs;
            this.executionOrder = Collections.unmodifiableList(executionOrder);
        }

        public int getTotalNodes() { return totalNodes; }
        public int getPassedCount() { return passedCount; }
        public int getFailedCount() { return failedCount; }
        public int getBlockedCount() { return blockedCount; }
        public int getSkippedCount() { return skippedCount; }
        public long getDurationMs() { return durationMs; }
        public List<String> getExecutionOrder() { return executionOrder; }

        public boolean isAllPassed() {
            return passedCount == totalNodes && failedCount == 0 && blockedCount == 0;
        }

        public boolean isAccounted() {
            return (passedCount + failedCount + blockedCount + skippedCount) == totalNodes;
        }
    }

    private final DependencyGraph graph;
    private final int maxConcurrency;

    public DagExecutionScheduler(DependencyGraph graph, int maxConcurrency) {
        this.graph = graph != null ? graph : new DependencyGraph();
        this.maxConcurrency = maxConcurrency > 0 ? maxConcurrency : 5;
    }

    public DagExecutionScheduler(DependencyGraph graph) {
        this(graph, 5);
    }

    /**
     * Executes the entire DAG using asynchronous ready queue coordination.
     */
    public DagExecutionSummary execute(ExecutionContext context, NodeExecutor executor) throws Exception {
        long startNanos = System.nanoTime();
        int totalNodes = graph.getNodeCount();

        if (totalNodes == 0) {
            return new DagExecutionSummary(0, 0, 0, 0, 0, 0, Collections.emptyList());
        }

        // 1. Break any accidental cycles before scheduling
        graph.detectAndBreakCycles();

        // 2. Initial ready nodes (in-degree == 0)
        List<DagNode> initialReady = graph.getInitialReadyNodes();
        BlockingQueue<DagNode> readyQueue = new LinkedBlockingQueue<>(initialReady);

        Set<String> completedNodeIds = ConcurrentHashMap.newKeySet();
        Set<String> failedNodeIds = ConcurrentHashMap.newKeySet();
        Set<String> blockedNodeIds = ConcurrentHashMap.newKeySet();
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicInteger accountedNodes = new AtomicInteger(0);
        AtomicBoolean isTerminated = new AtomicBoolean(false);
        CountDownLatch completionLatch = new CountDownLatch(1);

        Runnable triggerTermination = () -> {
            if (isTerminated.compareAndSet(false, true)) {
                completionLatch.countDown();
            }
        };

        ExecutorService threadPool = Executors.newFixedThreadPool(maxConcurrency);

        // Dispatch workers
        for (int i = 0; i < maxConcurrency; i++) {
            threadPool.submit(() -> {
                int idleCount = 0;
                while (!isTerminated.get()) {
                    DagNode node = null;
                    try {
                        node = readyQueue.poll(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (node == null) {
                        if (accountedNodes.get() >= totalNodes) {
                            triggerTermination.run();
                            break;
                        }
                        if (activeWorkers.get() == 0 && readyQueue.isEmpty()) {
                            idleCount++;
                            if (idleCount > 10) { // Sustained 500ms of zero work
                                triggerTermination.run();
                                break;
                            }
                        } else {
                            idleCount = 0;
                        }
                        continue;
                    }

                    idleCount = 0;
                    if (blockedNodeIds.contains(node.getId()) || failedNodeIds.contains(node.getId()) || completedNodeIds.contains(node.getId())) {
                        continue;
                    }

                    activeWorkers.incrementAndGet();
                    node.setStatus(DagNode.NodeStatus.RUNNING);
                    node.setStartedAt(OffsetDateTime.now());

                    DagNode.NodeStatus finalStatus;
                    try {
                        finalStatus = executor.execute(node, context);
                    } catch (Exception e) {
                        log.error("Exception during DAG node execution [{}]: {}", node.getId(), e.getMessage(), e);
                        finalStatus = DagNode.NodeStatus.FAILED;
                        node.setFailureReason("EXECUTION_EXCEPTION: " + e.getMessage());
                    }

                    node.setStatus(finalStatus);
                    node.setCompletedAt(OffsetDateTime.now());
                    executionOrder.add(node.getId());

                    if (finalStatus == DagNode.NodeStatus.PASSED) {
                        completedNodeIds.add(node.getId());
                        accountedNodes.incrementAndGet();

                        // Satisfy dependencies for downstream consumers
                        for (DagEdge edge : graph.getOutgoingEdges(node.getId())) {
                            DagNode consumer = graph.getNode(edge.getConsumerNodeId());
                            if (consumer != null && !blockedNodeIds.contains(consumer.getId()) && !completedNodeIds.contains(consumer.getId())) {
                                int remaining = consumer.decrementInDegree();
                                if (remaining <= 0) {
                                    consumer.setStatus(DagNode.NodeStatus.READY);
                                    readyQueue.add(consumer);
                                }
                            }
                        }
                    } else {
                        // Node Failed: Cascade BLOCKED status to all transitive downstream consumers
                        failedNodeIds.add(node.getId());
                        accountedNodes.incrementAndGet();

                        Set<String> downstream = graph.getTransitiveDownstreamNodeIds(node.getId());
                        for (String childId : downstream) {
                            DagNode child = graph.getNode(childId);
                            if (child != null && child.getStatus() != DagNode.NodeStatus.PASSED) {
                                if (blockedNodeIds.add(childId)) {
                                    child.setStatus(DagNode.NodeStatus.BLOCKED);
                                    child.setFailureReason("BLOCKED: Upstream producer [" + node.getName() + "] failed");
                                    accountedNodes.incrementAndGet();
                                }
                            }
                        }
                    }

                    activeWorkers.decrementAndGet();
                    if (accountedNodes.get() >= totalNodes) {
                        triggerTermination.run();
                    }
                }
            });
        }

        try {
            completionLatch.await(300, TimeUnit.SECONDS);
        } finally {
            threadPool.shutdownNow();
        }

        // 3. Mark any remaining unexecuted nodes as BLOCKED
        for (DagNode n : graph.getAllNodes()) {
            if (!n.isTerminal()) {
                n.setStatus(DagNode.NodeStatus.BLOCKED);
                n.setFailureReason("BLOCKED: Dependency satisfaction unresolvable or graph stalled");
                blockedNodeIds.add(n.getId());
            }
        }

        long durationMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
        int passedCount = completedNodeIds.size();
        int failedCount = failedNodeIds.size();
        int blockedCount = blockedNodeIds.size();
        int skippedCount = (int) graph.getAllNodes().stream().filter(n -> n.getStatus() == DagNode.NodeStatus.SKIPPED).count();

        return new DagExecutionSummary(
                totalNodes,
                passedCount,
                failedCount,
                blockedCount,
                skippedCount,
                durationMs,
                executionOrder
        );
    }
}
