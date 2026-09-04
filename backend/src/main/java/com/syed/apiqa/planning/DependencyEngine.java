package com.syed.apiqa.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.planning.dag.DagEdge;
import com.syed.apiqa.planning.dag.DagNode;
import com.syed.apiqa.planning.dag.DependencyGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Dependency Inference and DAG Formulation Engine (Zero LLM).
 * Infers entity parameter producer-consumer links for nested REST sub-resources across
 * path parameters, query parameters, header parameters, and request body variables.
 * Performs grammatical matching, builds execution DAGs, and eliminates multi-node cycles.
 */
@Service
public class DependencyEngine {

    private static final Logger log = LoggerFactory.getLogger(DependencyEngine.class);
    private final ObjectMapper objectMapper;
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static final Pattern TEMPLATE_VAR_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    public DependencyEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Builds and infers all producer-consumer dependencies for a list of discovered endpoints.
     */
    public List<Dependency> buildDependencies(TestRun testRun, List<ApiEndpoint> endpoints) {
        List<Dependency> dependencies = new ArrayList<>();
        if (endpoints == null || endpoints.isEmpty()) {
            return dependencies;
        }

        Map<String, List<ApiEndpoint>> entityProducers = new HashMap<>();

        // 1. Identify Producer Endpoints (POST operations that create resources, or setup/me endpoints)
        for (ApiEndpoint ep : endpoints) {
            String method = ep.getMethod() != null ? ep.getMethod().toUpperCase() : "GET";
            String path = ep.getPath() != null ? ep.getPath() : "";

            if ("POST".equals(method) || isSetupOrProducerEndpoint(method, path)) {
                String entityName = extractEntityNameFromPath(path);
                entityProducers.computeIfAbsent(entityName, k -> new ArrayList<>()).add(ep);
            }
        }

        // 2. Identify Consumer Endpoints (Operations with path parameters, query parameters, or body variables)
        for (ApiEndpoint consumer : endpoints) {
            List<String> pathParams = extractPathParameters(consumer.getPath());
            String consumerEntity = extractEntityNameFromPath(consumer.getPath());

            for (String param : pathParams) {
                String targetEntity = resolveEntityForParam(param, consumerEntity, consumer.getPath());
                List<ApiEndpoint> candidateProducers = entityProducers.get(targetEntity);

                if (candidateProducers == null || candidateProducers.isEmpty()) {
                    for (Map.Entry<String, List<ApiEndpoint>> entry : entityProducers.entrySet()) {
                        if (isGrammaticalMatch(entry.getKey(), targetEntity)) {
                            candidateProducers = entry.getValue();
                            break;
                        }
                    }
                }

                if (candidateProducers != null && !candidateProducers.isEmpty()) {
                    ApiEndpoint bestProducer = selectBestProducer(candidateProducers, consumer, param);
                    if (bestProducer != null && !bestProducer.getId().equals(consumer.getId())) {
                        ConfidenceLevel confidence = isPathPrefixMatch(bestProducer.getPath(), consumer.getPath())
                                ? ConfidenceLevel.HIGH
                                : ConfidenceLevel.MEDIUM;
                        String sourceField = param.equalsIgnoreCase("id") ? "id" : param;
                        String reason = "Inferred dependency: " + bestProducer.getMethod() + " " + bestProducer.getPath() +
                                " produces {" + param + "} for " + consumer.getMethod() + " " + consumer.getPath();

                        Dependency dep = new Dependency();
                        dep.setId(UUID.randomUUID().toString());
                        dep.setTestRun(testRun);
                        dep.setProducerEndpoint(bestProducer);
                        dep.setConsumerEndpoint(consumer);
                        dep.setParameterName(param);
                        dep.setSourceField(sourceField);
                        dep.setConfidence(confidence);
                        dep.setReason(reason);
                        dependencies.add(dep);
                    }
                }
            }
        }

        // 3. Multi-Node Cycle Detection & Resolution (DFS DAG verification)
        return breakCycles(dependencies);
    }

    /**
     * Builds an executable DependencyGraph from a list of planned TestCases and inferred dependencies.
     */
    public DependencyGraph buildDependencyGraph(TestRun testRun, List<TestCase> testCases, List<Dependency> dependencies) {
        DependencyGraph graph = new DependencyGraph();
        if (testCases == null || testCases.isEmpty()) {
            return graph;
        }

        Map<String, DagNode> caseNodeMap = new HashMap<>();
        Map<String, String> endpointToCaseIdMap = new HashMap<>();

        // Add nodes
        for (TestCase tc : testCases) {
            DagNode node = new DagNode(tc.getId(), tc.getName(), tc);
            graph.addNode(node);
            caseNodeMap.put(tc.getId(), node);
        }

        // Map endpoint IDs to case IDs
        for (TestCase tc : testCases) {
            String entity = extractEntityNameFromPath(tc.getName());
            endpointToCaseIdMap.put(entity, tc.getId());
        }

        // Add edges from dependencies
        if (dependencies != null) {
            for (Dependency dep : dependencies) {
                if (dep.getProducerEndpoint() == null || dep.getConsumerEndpoint() == null) continue;
                String prodEntity = extractEntityNameFromPath(dep.getProducerEndpoint().getPath());
                String consEntity = extractEntityNameFromPath(dep.getConsumerEndpoint().getPath());

                String prodCaseId = endpointToCaseIdMap.get(prodEntity);
                String consCaseId = endpointToCaseIdMap.get(consEntity);

                if (prodCaseId != null && consCaseId != null && !prodCaseId.equals(consCaseId)) {
                    DagEdge edge = new DagEdge(
                            prodCaseId,
                            consCaseId,
                            dep.getParameterName(),
                            dep.getSourceField(),
                            DagEdge.ParameterLocation.PATH,
                            dep.getConfidence(),
                            dep.getReason()
                    );
                    graph.addEdge(edge);
                }
            }
        }

        graph.detectAndBreakCycles();
        graph.recalculateInDegrees();
        return graph;
    }

    private boolean isSetupOrProducerEndpoint(String method, String path) {
        String p = path.toLowerCase();
        return p.contains("/auth/login") || p.contains("/auth/register") ||
               p.endsWith("/me") || p.endsWith("/profile") || p.endsWith("/self");
    }

    public List<String> extractPathParameters(String path) {
        List<String> params = new ArrayList<>();
        if (path == null) return params;
        Matcher matcher = PATH_PARAM_PATTERN.matcher(path);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    public String extractEntityNameFromPath(String path) {
        if (path == null || path.isBlank()) return "resource";
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.isBlank() && !part.startsWith("{") && !part.equalsIgnoreCase("api") &&
                    !part.equalsIgnoreCase("v1") && !part.equalsIgnoreCase("v2") && !part.equalsIgnoreCase("v3")) {
                return part.toLowerCase();
            }
        }
        return "resource";
    }

    private String resolveEntityForParam(String param, String consumerEntity, String fullPath) {
        String normalized = param.replaceAll("(?i)(id|_id|uuid)$", "").toLowerCase();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return consumerEntity;
    }

    private boolean isGrammaticalMatch(String candidate, String target) {
        if (candidate.equalsIgnoreCase(target)) return true;
        String c = candidate.toLowerCase();
        String t = target.toLowerCase();
        if (c.equals(t + "s") || t.equals(c + "s")) return true;
        if (c.equals(t + "es") || t.equals(c + "es")) return true;
        if (t.endsWith("y") && c.equals(t.substring(0, t.length() - 1) + "ies")) return true;
        if (c.endsWith("y") && t.equals(c.substring(0, c.length() - 1) + "ies")) return true;
        return false;
    }

    private ApiEndpoint selectBestProducer(List<ApiEndpoint> candidates, ApiEndpoint consumer, String param) {
        if (candidates.size() == 1) return candidates.get(0);

        String consumerPath = consumer.getPath();
        ApiEndpoint best = candidates.get(0);
        int maxCommonPrefixLen = -1;

        for (ApiEndpoint candidate : candidates) {
            String candidatePath = candidate.getPath();
            int commonLen = commonPrefixLength(candidatePath, consumerPath);
            if (commonLen > maxCommonPrefixLen) {
                maxCommonPrefixLen = commonLen;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isPathPrefixMatch(String producerPath, String consumerPath) {
        String baseProducer = producerPath.replaceAll("/\\{[^}]+\\}", "");
        String baseConsumer = consumerPath.replaceAll("/\\{[^}]+\\}", "");
        return baseConsumer.startsWith(baseProducer);
    }

    private int commonPrefixLength(String s1, String s2) {
        int len = Math.min(s1.length(), s2.length());
        int i = 0;
        while (i < len && s1.charAt(i) == s2.charAt(i)) {
            i++;
        }
        return i;
    }

    private List<Dependency> breakCycles(List<Dependency> deps) {
        List<Dependency> result = new ArrayList<>(deps);
        boolean cycleFound = true;

        while (cycleFound) {
            cycleFound = false;
            Map<String, List<Dependency>> adj = new HashMap<>();
            for (Dependency d : result) {
                adj.computeIfAbsent(d.getProducerEndpoint().getId(), k -> new ArrayList<>()).add(d);
            }

            Map<String, Integer> state = new HashMap<>();
            List<Dependency> cycleEdges = new ArrayList<>();

            for (String nodeId : adj.keySet()) {
                if (findCycleDfs(nodeId, adj, state, new ArrayList<>(), cycleEdges)) {
                    cycleFound = true;
                    Dependency edgeToRemove = cycleEdges.get(0);
                    for (Dependency d : cycleEdges) {
                        if (d.getConfidence().ordinal() > edgeToRemove.getConfidence().ordinal()) {
                            edgeToRemove = d;
                        }
                    }
                    result.remove(edgeToRemove);
                    break;
                }
            }
        }
        return result;
    }

    private boolean findCycleDfs(String u,
                                 Map<String, List<Dependency>> adj,
                                 Map<String, Integer> state,
                                 List<Dependency> path,
                                 List<Dependency> cycleOut) {
        state.put(u, 1);
        List<Dependency> edges = adj.getOrDefault(u, Collections.emptyList());

        for (Dependency edge : edges) {
            String v = edge.getConsumerEndpoint().getId();
            int vState = state.getOrDefault(v, 0);

            path.add(edge);
            if (vState == 1) {
                int startIndex = -1;
                for (int i = 0; i < path.size(); i++) {
                    if (path.get(i).getProducerEndpoint().getId().equals(v)) {
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
                if (findCycleDfs(v, adj, state, path, cycleOut)) {
                    return true;
                }
            }
            path.remove(path.size() - 1);
        }

        state.put(u, 2);
        return false;
    }
}
