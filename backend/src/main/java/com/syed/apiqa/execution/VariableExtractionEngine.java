package com.syed.apiqa.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic Runtime Variable Extraction Engine.
 * Supports path-directed JSONPath / JSON Pointer / dot-notation extraction and
 * recursive schema/response-aware variable discovery across arbitrary unknown APIs.
 * Preserves runtime types (STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY, NULL, UUID),
 * distinguishes FOUND_NULL from NOT_FOUND, isolates variables, and attaches full provenance.
 */
@Service
public class VariableExtractionEngine {

    private static final Logger log = LoggerFactory.getLogger(VariableExtractionEngine.class);
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ARRAY_INDEX_PATTERN = Pattern.compile("^(.*?)\\[(\\d+)\\]$");

    public enum VariableType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        OBJECT,
        ARRAY,
        NULL,
        UUID
    }

    public enum ExtractionStatus {
        FOUND,
        FOUND_NULL,
        NOT_FOUND,
        EXTRACTION_ERROR,
        UNSUPPORTED
    }

    public static class ExtractedVariable {
        private final String name;
        private final String canonicalPath;
        private final VariableType type;
        private final Object rawValue;
        private final String stringValue;
        private final boolean sensitive;
        private final ExecutionContext.VariableProvenance provenance;
        private final OffsetDateTime capturedAt;

        public ExtractedVariable(String name,
                                 String canonicalPath,
                                 VariableType type,
                                 Object rawValue,
                                 String stringValue,
                                 boolean sensitive,
                                 ExecutionContext.VariableProvenance provenance) {
            this.name = name;
            this.canonicalPath = canonicalPath;
            this.type = type;
            this.rawValue = rawValue;
            this.stringValue = stringValue;
            this.sensitive = sensitive;
            this.provenance = provenance;
            this.capturedAt = OffsetDateTime.now();
        }

        public String getName() { return name; }
        public String getCanonicalPath() { return canonicalPath; }
        public VariableType getType() { return type; }
        public Object getRawValue() { return rawValue; }
        public String getStringValue() { return stringValue; }
        public boolean isSensitive() { return sensitive; }
        public ExecutionContext.VariableProvenance getProvenance() { return provenance; }
        public OffsetDateTime getCapturedAt() { return capturedAt; }
    }

    public static class ExtractionResult {
        private final ExtractionStatus status;
        private final ExtractedVariable variable;
        private final String errorMessage;

        private ExtractionResult(ExtractionStatus status, ExtractedVariable variable, String errorMessage) {
            this.status = status;
            this.variable = variable;
            this.errorMessage = errorMessage;
        }

        public static ExtractionResult found(ExtractedVariable var) {
            return new ExtractionResult(ExtractionStatus.FOUND, var, null);
        }

        public static ExtractionResult foundNull(String path) {
            return new ExtractionResult(ExtractionStatus.FOUND_NULL,
                    new ExtractedVariable(path, path, VariableType.NULL, null, null, false, null), null);
        }

        public static ExtractionResult notFound(String path) {
            return new ExtractionResult(ExtractionStatus.NOT_FOUND, null, "Path not found in response JSON: " + path);
        }

        public static ExtractionResult unsupported(String path, String reason) {
            return new ExtractionResult(ExtractionStatus.UNSUPPORTED, null, reason);
        }

        public static ExtractionResult error(String path, String error) {
            return new ExtractionResult(ExtractionStatus.EXTRACTION_ERROR, null, error);
        }

        public ExtractionStatus getStatus() { return status; }
        public ExtractedVariable getVariable() { return variable; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isSuccess() { return status == ExtractionStatus.FOUND || status == ExtractionStatus.FOUND_NULL; }
    }

    private final ObjectMapper objectMapper;

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password", "token", "secret", "api_key", "apikey", "access_token",
            "refresh_token", "cookie", "authorization", "session", "csrf",
            "private_key", "client_secret", "credentials", "auth_token", "passphrase"
    );

    public VariableExtractionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Extracts a single variable using a canonical path (e.g. data.id, items[0].id, $.data.id, /data/id).
     */
    public ExtractionResult extractByPath(JsonNode root, String rawPath) {
        if (root == null) {
            return ExtractionResult.notFound(rawPath);
        }
        if (rawPath == null || rawPath.isBlank()) {
            return extractNodeAsResult(root, "$", "$", null, null);
        }

        String normalized = rawPath.trim();
        // Check for wildcards
        if (normalized.contains("[*]") || normalized.contains("..")) {
            return ExtractionResult.unsupported(rawPath, "Wildcard [*] or recursive descent (..) extraction is unsupported for deterministic single-value binding");
        }

        List<String> tokens = parsePathTokens(normalized);
        if (tokens.isEmpty()) {
            return extractNodeAsResult(root, normalized, normalized, null, null);
        }

        JsonNode current = root;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (current == null || current.isMissingNode()) {
                return ExtractionResult.notFound(rawPath);
            }

            if (current.isArray()) {
                try {
                    int index = Integer.parseInt(token);
                    if (index < 0 || index >= current.size()) {
                        return ExtractionResult.notFound(rawPath + " (index " + index + " out of bounds, size=" + current.size() + ")");
                    }
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return ExtractionResult.notFound(rawPath + " (expected array index at segment: " + token + ")");
                }
            } else if (current.isObject()) {
                if (!current.has(token)) {
                    return ExtractionResult.notFound(rawPath);
                }
                current = current.get(token);
            } else {
                // Scalar node cannot be traversed further
                return ExtractionResult.notFound(rawPath);
            }
        }

        if (current == null || current.isMissingNode()) {
            return ExtractionResult.notFound(rawPath);
        }
        if (current.isNull()) {
            return ExtractionResult.foundNull(rawPath);
        }

        return extractNodeAsResult(current, rawPath, rawPath, null, null);
    }

    /**
     * Recursively extracts all relevant variables and aliases from a response body JSON.
     */
    public List<ExtractedVariable> extractAll(JsonNode root,
                                              String entityPrefix,
                                              String endpoint,
                                              String stepName,
                                              String identityName) {
        List<ExtractedVariable> results = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return results;
        }

        String entity = normalizeEntityPrefix(entityPrefix);

        // 1. Root scalar response
        if (root.isValueNode()) {
            ExtractionResult res = extractNodeAsResult(root, entity, "$", endpoint, identityName);
            if (res.isSuccess() && res.getVariable() != null) {
                results.add(res.getVariable());
            }
            return results;
        }

        // 2. Root array response
        if (root.isArray()) {
            // Save array itself
            String arrayJson = stringify(root);
            results.add(new ExtractedVariable(entity + ".items", "$", VariableType.ARRAY, root, arrayJson, false,
                    new ExecutionContext.VariableProvenance(entity + ".items", arrayJson, endpoint, "$", identityName)));
            results.add(new ExtractedVariable("items", "$", VariableType.ARRAY, root, arrayJson, false,
                    new ExecutionContext.VariableProvenance("items", arrayJson, endpoint, "$", identityName)));

            // Extract first few items
            int limit = Math.min(root.size(), 3);
            for (int i = 0; i < limit; i++) {
                JsonNode item = root.get(i);
                String itemPath = "[" + i + "]";
                if (item.isObject()) {
                    traverseObject(item, itemPath, entity, endpoint, identityName, results, 0);
                } else if (item.isValueNode()) {
                    ExtractionResult res = extractNodeAsResult(item, entity + "[" + i + "]", itemPath, endpoint, identityName);
                    if (res.isSuccess() && res.getVariable() != null) {
                        results.add(res.getVariable());
                    }
                }
            }
            return results;
        }

        // 3. Root object response
        if (root.isObject()) {
            traverseObject(root, "", entity, endpoint, identityName, results, 0);

            // Special Envelope Unwrapping: data, result, response, payload, item, resource
            List<String> envelopes = List.of("data", "result", "response", "payload", "item", "resource");
            for (String env : envelopes) {
                if (root.has(env)) {
                    JsonNode envNode = root.get(env);
                    if (envNode.isObject()) {
                        traverseObject(envNode, env, entity, endpoint, identityName, results, 0);
                    } else if (envNode.isArray()) {
                        String envArrayJson = stringify(envNode);
                        results.add(new ExtractedVariable(entity + "." + env, env, VariableType.ARRAY, envNode, envArrayJson, false,
                                new ExecutionContext.VariableProvenance(entity + "." + env, envArrayJson, endpoint, env, identityName)));
                        if (envNode.size() > 0 && envNode.get(0).isObject()) {
                            traverseObject(envNode.get(0), env + "[0]", entity, endpoint, identityName, results, 0);
                        }
                    }
                }
            }
        }

        return results;
    }

    private void traverseObject(JsonNode obj,
                                String currentPath,
                                String entity,
                                String endpoint,
                                String identityName,
                                List<ExtractedVariable> collector,
                                int depth) {
        if (obj == null || !obj.isObject() || depth > 5) return;

        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();

            String fullPath = currentPath.isEmpty() ? key : currentPath + "." + key;
            boolean sensitive = isSensitive(key);

            if (val.isValueNode()) {
                if (!val.isNull()) {
                    ExtractionResult res = extractNodeAsResult(val, fullPath, fullPath, endpoint, identityName);
                    if (res.isSuccess() && res.getVariable() != null) {
                        collector.add(res.getVariable());

                        // Also create entity-scoped name: entity.field
                        String scopedName = entity + "." + key;
                        if (!scopedName.equals(fullPath)) {
                            collector.add(new ExtractedVariable(scopedName, fullPath, res.getVariable().getType(),
                                    res.getVariable().getRawValue(), res.getVariable().getStringValue(), sensitive,
                                    new ExecutionContext.VariableProvenance(scopedName, res.getVariable().getStringValue(), endpoint, fullPath, identityName)));
                        }

                        // Create ID aliases for primary identifiers
                        if (isIdentifierField(key)) {
                            collector.add(new ExtractedVariable("id", fullPath, res.getVariable().getType(),
                                    res.getVariable().getRawValue(), res.getVariable().getStringValue(), sensitive,
                                    new ExecutionContext.VariableProvenance("id", res.getVariable().getStringValue(), endpoint, fullPath, identityName)));
                            collector.add(new ExtractedVariable(entity + "_id", fullPath, res.getVariable().getType(),
                                    res.getVariable().getRawValue(), res.getVariable().getStringValue(), sensitive,
                                    new ExecutionContext.VariableProvenance(entity + "_id", res.getVariable().getStringValue(), endpoint, fullPath, identityName)));
                            collector.add(new ExtractedVariable(entity + "Id", fullPath, res.getVariable().getType(),
                                    res.getVariable().getRawValue(), res.getVariable().getStringValue(), sensitive,
                                    new ExecutionContext.VariableProvenance(entity + "Id", res.getVariable().getStringValue(), endpoint, fullPath, identityName)));
                        }
                    }
                }
            } else if (val.isObject()) {
                // Save structured object node as JSON string
                String objJson = stringify(val);
                collector.add(new ExtractedVariable(fullPath, fullPath, VariableType.OBJECT, val, objJson, sensitive,
                        new ExecutionContext.VariableProvenance(fullPath, objJson, endpoint, fullPath, identityName)));
                collector.add(new ExtractedVariable(entity + "." + key, fullPath, VariableType.OBJECT, val, objJson, sensitive,
                        new ExecutionContext.VariableProvenance(entity + "." + key, objJson, endpoint, fullPath, identityName)));

                // Recurse into nested object
                traverseObject(val, fullPath, entity, endpoint, identityName, collector, depth + 1);

            } else if (val.isArray()) {
                // Save structured array node as JSON string
                String arrJson = stringify(val);
                collector.add(new ExtractedVariable(fullPath, fullPath, VariableType.ARRAY, val, arrJson, sensitive,
                        new ExecutionContext.VariableProvenance(fullPath, arrJson, endpoint, fullPath, identityName)));
                collector.add(new ExtractedVariable(entity + "." + key, fullPath, VariableType.ARRAY, val, arrJson, sensitive,
                        new ExecutionContext.VariableProvenance(entity + "." + key, arrJson, endpoint, fullPath, identityName)));

                // Recurse into first item if it's an object or scalar
                if (val.size() > 0) {
                    JsonNode firstItem = val.get(0);
                    if (firstItem.isObject()) {
                        traverseObject(firstItem, fullPath + "[0]", entity, endpoint, identityName, collector, depth + 1);
                    } else if (firstItem.isValueNode() && !firstItem.isNull()) {
                        ExtractionResult firstRes = extractNodeAsResult(firstItem, fullPath + "[0]", fullPath + "[0]", endpoint, identityName);
                        if (firstRes.isSuccess() && firstRes.getVariable() != null) {
                            collector.add(firstRes.getVariable());
                        }
                    }
                }
            }
        }
    }

    private ExtractionResult extractNodeAsResult(JsonNode node, String varName, String path, String endpoint, String identityName) {
        if (node == null || node.isMissingNode()) {
            return ExtractionResult.notFound(path);
        }
        if (node.isNull()) {
            return ExtractionResult.foundNull(path);
        }

        VariableType type;
        Object rawVal;
        String stringVal;
        boolean sensitive = isSensitive(varName) || isSensitive(path);

        if (node.isTextual()) {
            String text = node.asText();
            if (UUID_PATTERN.matcher(text).matches()) {
                type = VariableType.UUID;
            } else {
                type = VariableType.STRING;
            }
            rawVal = text;
            stringVal = text;
        } else if (node.isInt() || node.isLong() || node.isBigInteger() || node.isIntegralNumber()) {
            type = VariableType.INTEGER;
            rawVal = node.asLong();
            stringVal = node.asText();
        } else if (node.isFloatingPointNumber() || node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            type = VariableType.NUMBER;
            rawVal = node.asDouble();
            stringVal = node.asText();
        } else if (node.isBoolean()) {
            type = VariableType.BOOLEAN;
            rawVal = node.asBoolean();
            stringVal = node.asText();
        } else if (node.isObject()) {
            type = VariableType.OBJECT;
            rawVal = node;
            stringVal = stringify(node);
        } else if (node.isArray()) {
            type = VariableType.ARRAY;
            rawVal = node;
            stringVal = stringify(node);
        } else {
            type = VariableType.STRING;
            rawVal = node.asText();
            stringVal = node.asText();
        }

        ExecutionContext.VariableProvenance prov = new ExecutionContext.VariableProvenance(
                varName, stringVal, endpoint, path, identityName
        );

        return ExtractionResult.found(new ExtractedVariable(varName, path, type, rawVal, stringVal, sensitive, prov));
    }

    public boolean isSensitive(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase().replaceAll("[^a-z0-9_]", "");
        for (String key : SENSITIVE_KEYWORDS) {
            if (lower.contains(key)) return true;
        }
        return false;
    }

    public boolean isIdentifierField(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.equals("id") || lower.equals("uuid") || lower.equals("_id") ||
                (lower.endsWith("id") && lower.length() <= 15) ||
                (lower.endsWith("_id") && lower.length() <= 15);
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private String normalizeEntityPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "entity";
        String p = prefix.trim().toLowerCase();
        if (p.startsWith("/")) p = p.substring(1);
        String[] parts = p.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String seg = parts[i].trim();
            if (!seg.isBlank() && !seg.startsWith("{") && !seg.equalsIgnoreCase("api") &&
                    !seg.equalsIgnoreCase("v1") && !seg.equalsIgnoreCase("v2") && !seg.equalsIgnoreCase("v3")) {
                return seg;
            }
        }
        return "entity";
    }

    public List<String> parsePathTokens(String path) {
        List<String> tokens = new ArrayList<>();
        if (path == null || path.isBlank()) return tokens;

        String clean = path.trim();
        if (clean.startsWith("$.")) {
            clean = clean.substring(2);
        } else if (clean.startsWith("$")) {
            clean = clean.substring(1);
        }
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }

        if (clean.isBlank()) return tokens;

        // Split on '.' and '/'
        String[] dotSegments = clean.split("[./]");
        for (String segment : dotSegments) {
            if (segment.isBlank()) continue;

            // Check if segment has array index like items[0]
            Matcher m = ARRAY_INDEX_PATTERN.matcher(segment);
            if (m.matches()) {
                String prefix = m.group(1).trim();
                String index = m.group(2).trim();
                if (!prefix.isEmpty()) {
                    tokens.add(prefix);
                }
                tokens.add(index);
            } else if (segment.startsWith("[") && segment.endsWith("]")) {
                String index = segment.substring(1, segment.length() - 1).trim();
                tokens.add(index);
            } else {
                tokens.add(segment);
            }
        }
        return tokens;
    }
}
