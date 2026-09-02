package com.syed.apiqa.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.ConfidenceLevel;
import com.syed.apiqa.domain.Dependency;
import com.syed.apiqa.domain.TestRun;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Dependency Inference Engine (Zero LLM).
 * Infers entity parameter producer-consumer links for nested REST sub-resources,
 * performs grammatical singular/plural entity matching, and detects/breaks multi-node cycles via DFS DAG verification.
 */
@Service
public class DependencyEngine {

    private final ObjectMapper objectMapper;
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    public DependencyEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Dependency> buildDependencies(TestRun testRun, List<ApiEndpoint> endpoints) {
        List<Dependency> dependencies = new ArrayList<>();
        Map<String, List<ApiEndpoint>> entityProducers = new HashMap<>();

        // 1. Identify Producer Endpoints (primarily POST operations that create resources)
        for (ApiEndpoint ep : endpoints) {
            if ("POST".equalsIgnoreCase(ep.getMethod())) {
                String entityName = extractEntityNameFromPath(ep.getPath());
                entityProducers.computeIfAbsent(entityName, k -> new ArrayList<>()).add(ep);
            }
        }

        // 2. Identify Consumer Endpoints (Operations with path parameters like {id}, {userId}, {orderId})
        for (ApiEndpoint consumer : endpoints) {
            List<String> pathParams = extractPathParameters(consumer.getPath());
            if (pathParams.isEmpty()) continue;

            String consumerEntity = extractEntityNameFromPath(consumer.getPath());

            for (String param : pathParams) {
                ApiEndpoint bestProducer = null;
                ConfidenceLevel confidence = ConfidenceLevel.LOW;
                String sourceField = "id";
                String reason = "";

                // Determine target entity name for this specific parameter
                String targetEntity = resolveEntityForParam(param, consumerEntity, consumer.getPath());

                List<ApiEndpoint> candidateProducers = entityProducers.get(targetEntity);
                if (candidateProducers == null || candidateProducers.isEmpty()) {
                    // Try plural/singular or grammatical match
                    for (Map.Entry<String, List<ApiEndpoint>> entry : entityProducers.entrySet()) {
                        if (isGrammaticalMatch(entry.getKey(), targetEntity)) {
                            candidateProducers = entry.getValue();
                            break;
                        }
                    }
                }

                if (candidateProducers != null && !candidateProducers.isEmpty()) {
                    // Pick best producer matching path hierarchy if multiple exist
                    bestProducer = selectBestProducer(candidateProducers, consumer, param);
                    confidence = isPathPrefixMatch(bestProducer.getPath(), consumer.getPath())
                            ? ConfidenceLevel.HIGH
                            : ConfidenceLevel.MEDIUM;
                    sourceField = param.equalsIgnoreCase("id") ? "id" : param;
                    reason = "Entity match between " + bestProducer.getMethod() + " " + bestProducer.getPath() +
                            " and " + consumer.getMethod() + " " + consumer.getPath() + " for {" + param + "}";
                }

                if (bestProducer != null && !bestProducer.getId().equals(consumer.getId())) {
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

        // 3. Multi-Node Cycle Detection & Resolution (DFS DAG verification)
        return breakCycles(dependencies);
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

    /**
     * Extracts the target entity operated on by this path.
     * For nested sub-resources (e.g. /orders/{orderId}/items/{itemId}),
     * returns the terminal non-parameter segment ("items").
     */
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

    /**
     * Resolves the entity associated with a path parameter.
     * E.g. in /orders/{orderId}/items/{itemId}, {orderId} resolves to "orders", while {itemId} resolves to "items".
     */
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

    /**
     * Multi-node Cycle Detection and Breaking (DFS Graph verification).
     * Eliminates cycles (A -> B -> C -> A) by pruning the lowest confidence edge in each cycle.
     */
    private List<Dependency> breakCycles(List<Dependency> deps) {
        List<Dependency> result = new ArrayList<>(deps);
        boolean cycleFound = true;

        while (cycleFound) {
            cycleFound = false;
            // Build adjacency map: producerId -> list of outgoing dependencies
            Map<String, List<Dependency>> adj = new HashMap<>();
            for (Dependency d : result) {
                adj.computeIfAbsent(d.getProducerEndpoint().getId(), k -> new ArrayList<>()).add(d);
            }

            // State: 0 = unvisited, 1 = visiting (in current DFS stack), 2 = visited
            Map<String, Integer> state = new HashMap<>();
            List<Dependency> cycleEdges = new ArrayList<>();

            for (String nodeId : adj.keySet()) {
                if (findCycleDfs(nodeId, adj, state, new ArrayList<>(), cycleEdges)) {
                    cycleFound = true;
                    // Find edge with lowest confidence in the cycle
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
        state.put(u, 1); // Mark visiting
        List<Dependency> edges = adj.getOrDefault(u, Collections.emptyList());

        for (Dependency edge : edges) {
            String v = edge.getConsumerEndpoint().getId();
            int vState = state.getOrDefault(v, 0);

            path.add(edge);
            if (vState == 1) {
                // Back-edge found: cycle detected!
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

        state.put(u, 2); // Mark visited
        return false;
    }
}
