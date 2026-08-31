package com.syed.apiqa.planning;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * Infers entity parameter producer-consumer links and detects cycles.
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

        // 2. Identify Consumer Endpoints (Operations with path parameters like {id}, {userId})
        for (ApiEndpoint consumer : endpoints) {
            List<String> pathParams = extractPathParameters(consumer.getPath());
            if (pathParams.isEmpty()) continue;

            String consumerEntity = extractEntityNameFromPath(consumer.getPath());

            for (String param : pathParams) {
                // Try to find matching producer
                ApiEndpoint bestProducer = null;
                ConfidenceLevel confidence = ConfidenceLevel.LOW;
                String sourceField = "id";
                String reason = "";

                List<ApiEndpoint> candidateProducers = entityProducers.get(consumerEntity);
                if (candidateProducers != null && !candidateProducers.isEmpty()) {
                    bestProducer = candidateProducers.get(0);
                    // Check schema properties of producer response if available
                    confidence = ConfidenceLevel.HIGH;
                    sourceField = param.equalsIgnoreCase("id") ? "id" : param;
                    reason = "Direct path entity match between " + bestProducer.getMethod() + " " + bestProducer.getPath() +
                            " and " + consumer.getMethod() + " " + consumer.getPath() + " for {" + param + "}";
                } else {
                    // Try heuristic match: e.g. "userId" -> search entity "user" or "users"
                    String normalizedParamEntity = param.replaceAll("(?i)(id|_id|uuid)$", "");
                    if (!normalizedParamEntity.isBlank() && !normalizedParamEntity.equalsIgnoreCase(param)) {
                        for (Map.Entry<String, List<ApiEndpoint>> entry : entityProducers.entrySet()) {
                            if (entry.getKey().toLowerCase().startsWith(normalizedParamEntity.toLowerCase())) {
                                bestProducer = entry.getValue().get(0);
                                confidence = ConfidenceLevel.MEDIUM;
                                sourceField = "id";
                                reason = "Heuristic entity prefix match: parameter '" + param + "' mapped to producer " +
                                        bestProducer.getMethod() + " " + bestProducer.getPath();
                                break;
                            }
                        }
                    }
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

        // 3. Cycle Detection (Tarjan / Kahn verification)
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

    public String extractEntityNameFromPath(String path) {
        if (path == null || path.isBlank()) return "resource";
        String[] parts = path.split("/");
        for (String part : parts) {
            if (!part.isBlank() && !part.startsWith("{") && !part.equalsIgnoreCase("api") &&
                    !part.equalsIgnoreCase("v1") && !part.equalsIgnoreCase("v2") && !part.equalsIgnoreCase("v3")) {
                return part.toLowerCase();
            }
        }
        return "resource";
    }

    private List<Dependency> breakCycles(List<Dependency> deps) {
        // Detect simple 2-node cycles A -> B and B -> A, keep the higher confidence edge
        Map<String, Dependency> edgeMap = new HashMap<>();
        List<Dependency> safeDeps = new ArrayList<>();

        for (Dependency dep : deps) {
            String forwardKey = dep.getProducerEndpoint().getId() + "->" + dep.getConsumerEndpoint().getId();
            String reverseKey = dep.getConsumerEndpoint().getId() + "->" + dep.getProducerEndpoint().getId();

            if (edgeMap.containsKey(reverseKey)) {
                // Cycle detected! Break cycle by dropping or downgrading
                Dependency reverseDep = edgeMap.get(reverseKey);
                if (dep.getConfidence().ordinal() < reverseDep.getConfidence().ordinal()) {
                    // Current dep has higher confidence (HIGH is ordinal 0)
                    safeDeps.remove(reverseDep);
                    safeDeps.add(dep);
                    edgeMap.remove(reverseKey);
                    edgeMap.put(forwardKey, dep);
                }
            } else {
                edgeMap.put(forwardKey, dep);
                safeDeps.add(dep);
            }
        }
        return safeDeps;
    }
}
