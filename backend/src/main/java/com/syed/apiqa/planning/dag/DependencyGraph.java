package com.syed.apiqa.planning.dag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directed Acyclic Graph (DAG) for dependency resolution and execution coordination.
 * Enforces cycle elimination, edge deduplication, in-degree calculation,
 * topological sorting, and transitive downstream failure closure.
 */
public class DependencyGraph implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraph.class);

    private final Map<String, DagNode> nodes = new LinkedHashMap<>();
    private final Map<String, List<DagEdge>> outgoingEdges = new HashMap<>();
    private final Map<String, List<DagEdge>> incomingEdges = new HashMap<>();
    private final Set<DagEdge> edgeSet = new HashSet<>();

    public DependencyGraph() {}

    public synchronized void addNode(DagNode node) {
        if (node != null && node.getId() != null) {
            nodes.put(node.getId(), node);
            outgoingEdges.putIfAbsent(node.getId(), new ArrayList<>());
            incomingEdges.putIfAbsent(node.getId(), new ArrayList<>());
        }
    }

    public synchronized void addNode(String id, String name, Object payload) {
        addNode(new DagNode(id, name, payload));
    }

    public synchronized boolean addEdge(DagEdge edge) {
        if (edge == null) return false;
        String from = edge.getProducerNodeId();
        String to = edge.getConsumerNodeId();

        // Prevent self-dependencies
        if (from == null || to == null || from.equals(to)) {
            return false;
        }

        // Ensure nodes exist
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            return false;
        }

        // Deduplicate
        if (edgeSet.contains(edge)) {
            return false;
        }

        edgeSet.add(edge);
        outgoingEdges.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
        incomingEdges.computeIfAbsent(to, k -> new ArrayList<>()).add(edge);

        return true;
    }

    public synchronized boolean addEdge(String from, String to, String parameterName) {
        return addEdge(new DagEdge(from, to, parameterName));
    }

    public synchronized void recalculateInDegrees() {
        for (DagNode node : nodes.values()) {
            List<DagEdge> in = incomingEdges.getOrDefault(node.getId(), Collections.emptyList());
            node.setInDegree(in.size());
            if (in.isEmpty() && node.getStatus() == DagNode.NodeStatus.PENDING) {
                node.setStatus(DagNode.NodeStatus.READY);
            }
        }
    }

    public synchronized List<DagNode> getInitialReadyNodes() {
        recalculateInDegrees();
        List<DagNode> ready = new ArrayList<>();
        for (DagNode node : nodes.values()) {
            if (node.getInDegree() == 0) {
                ready.add(node);
            }
        }
        return ready;
    }

    public synchronized List<DagEdge> getOutgoingEdges(String nodeId) {
        return Collections.unmodifiableList(outgoingEdges.getOrDefault(nodeId, Collections.emptyList()));
    }

    public synchronized List<DagEdge> getIncomingEdges(String nodeId) {
        return Collections.unmodifiableList(incomingEdges.getOrDefault(nodeId, Collections.emptyList()));
    }

    public synchronized DagNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public synchronized Collection<DagNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public synchronized int getNodeCount() {
        return nodes.size();
    }

    public synchronized int getEdgeCount() {
        return edgeSet.size();
    }

    /**
     * Multi-node Cycle Detection and Breaking (DFS Graph verification).
     * Eliminates cycles (A -> B -> C -> A) by pruning the lowest confidence edge in each cycle.
     */
    public synchronized int detectAndBreakCycles() {
        int brokenCycles = 0;
        boolean cycleFound = true;

        while (cycleFound) {
            cycleFound = false;
            // State: 0 = unvisited, 1 = visiting, 2 = visited
            Map<String, Integer> state = new HashMap<>();
            List<DagEdge> path = new ArrayList<>();
            List<DagEdge> cycleEdges = new ArrayList<>();

            for (String nodeId : nodes.keySet()) {
                if (state.getOrDefault(nodeId, 0) == 0) {
                    if (findCycleDfs(nodeId, state, path, cycleEdges)) {
                        cycleFound = true;
                        brokenCycles++;

                        // Find edge with lowest confidence in the cycle
                        DagEdge edgeToRemove = cycleEdges.get(0);
                        for (DagEdge edge : cycleEdges) {
                            if (edge.getConfidence().ordinal() > edgeToRemove.getConfidence().ordinal()) {
                                edgeToRemove = edge;
                            }
                        }

                        log.warn("Detected dependency cycle! Pruning edge: {} -> {}",
                                edgeToRemove.getProducerNodeId(), edgeToRemove.getConsumerNodeId());
                        removeEdge(edgeToRemove);
                        break;
                    }
                }
            }
        }

        recalculateInDegrees();
        return brokenCycles;
    }

    private boolean findCycleDfs(String u,
                                 Map<String, Integer> state,
                                 List<DagEdge> path,
                                 List<DagEdge> cycleOut) {
        state.put(u, 1);
        List<DagEdge> edges = outgoingEdges.getOrDefault(u, Collections.emptyList());

        for (DagEdge edge : edges) {
            String v = edge.getConsumerNodeId();
            int vState = state.getOrDefault(v, 0);

            path.add(edge);
            if (vState == 1) {
                // Cycle detected
                int startIndex = -1;
                for (int i = 0; i < path.size(); i++) {
                    if (path.get(i).getProducerNodeId().equals(v)) {
                        startIndex = i;
                        break;
                    }
                }
                if (startIndex != -1) {
                    cycleOut.addAll(path.subList(startIndex, path.size()));
                } else {
                    cycleOut.addAll(path);
                }
                return true;
            } else if (vState == 0) {
                if (findCycleDfs(v, state, path, cycleOut)) {
                    return true;
                }
            }
            path.remove(path.size() - 1);
        }

        state.put(u, 2);
        return false;
    }

    public synchronized void removeEdge(DagEdge edge) {
        if (edge == null) return;
        edgeSet.remove(edge);
        List<DagEdge> out = outgoingEdges.get(edge.getProducerNodeId());
        if (out != null) out.remove(edge);
        List<DagEdge> in = incomingEdges.get(edge.getConsumerNodeId());
        if (in != null) in.remove(edge);
    }

    /**
     * Computes the full topological execution order of the graph using Kahn's algorithm.
     * Throws IllegalStateException if cycles remain.
     */
    public synchronized List<DagNode> getTopologicalOrder() {
        detectAndBreakCycles();
        Map<String, Integer> tempInDegrees = new HashMap<>();
        Queue<DagNode> queue = new LinkedList<>();

        for (DagNode node : nodes.values()) {
            int in = incomingEdges.getOrDefault(node.getId(), Collections.emptyList()).size();
            tempInDegrees.put(node.getId(), in);
            if (in == 0) {
                queue.add(node);
            }
        }

        List<DagNode> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            DagNode u = queue.poll();
            order.add(u);

            for (DagEdge edge : outgoingEdges.getOrDefault(u.getId(), Collections.emptyList())) {
                String vId = edge.getConsumerNodeId();
                int currentIn = tempInDegrees.get(vId) - 1;
                tempInDegrees.put(vId, currentIn);
                if (currentIn == 0) {
                    queue.add(nodes.get(vId));
                }
            }
        }

        if (order.size() != nodes.size()) {
            throw new IllegalStateException("Dependency graph contains unresolvable cycles. Expected " +
                    nodes.size() + " nodes, topological order has " + order.size());
        }

        return order;
    }

    /**
     * Returns the transitive downstream closure of dependent nodes starting from a failed producer.
     * Used to deterministically cascade BLOCKED status to all dependents while leaving independent nodes active.
     */
    public synchronized Set<String> getTransitiveDownstreamNodeIds(String startNodeId) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startNodeId);

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            for (DagEdge edge : outgoingEdges.getOrDefault(curr, Collections.emptyList())) {
                String child = edge.getConsumerNodeId();
                if (visited.add(child)) {
                    queue.add(child);
                }
            }
        }
        return visited;
    }
}
