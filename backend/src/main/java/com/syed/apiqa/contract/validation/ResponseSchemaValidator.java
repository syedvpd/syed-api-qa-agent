package com.syed.apiqa.contract.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal Response Schema Validator.
 * Validates actual response JSON AST against declared contract OpenAPI response schemas,
 * with full generic support for OpenAPI 3.0/3.1 composition (anyOf, oneOf, allOf),
 * recursive $ref dereferencing, nullable types, array containers, and structured findings.
 */
@Service
public class ResponseSchemaValidator {

    private static final int MAX_VALIDATION_DEPTH = 32;
    private static final int MAX_ARRAY_ITEMS_CHECK = 50;

    public List<SchemaValidationFinding> validate(JsonNode actualBody, Schema<?> expectedSchema, Map<String, Schema> componentSchemas) {
        List<SchemaValidationFinding> findings = new ArrayList<>();
        if (expectedSchema == null || actualBody == null) {
            return findings;
        }

        Set<String> visitedRefs = new HashSet<>();
        validateNode(actualBody, expectedSchema, "$", componentSchemas, findings, visitedRefs, 0);
        return findings;
    }

    private void validateNode(JsonNode node, Schema<?> schema, String path,
                              Map<String, Schema> componentSchemas,
                              List<SchemaValidationFinding> findings,
                              Set<String> visitedRefs,
                              int depth) {
        if (schema == null || node == null) return;

        if (depth > MAX_VALIDATION_DEPTH) {
            return; // Safe recursion limit reached
        }

        // Null handling & Nullable checks
        if (node.isNull()) {
            if (isNullableSchema(schema, componentSchemas)) {
                return; // Null is allowed
            } else {
                findings.add(new SchemaValidationFinding(
                        path, "Non-null value", "null", "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                ));
                return;
            }
        }

        // Dereference $ref
        if (schema.get$ref() != null && componentSchemas != null) {
            String refKey = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            String cycleKey = path + "->" + refKey;
            if (visitedRefs.contains(cycleKey)) {
                return; // Break recursion cycle safely
            }
            visitedRefs.add(cycleKey);

            Schema<?> target = componentSchemas.get(refKey);
            if (target != null) {
                validateNode(node, target, path, componentSchemas, findings, visitedRefs, depth + 1);
                return;
            } else {
                findings.add(new SchemaValidationFinding(
                        path, "Resolvable $ref: " + schema.get$ref(), "UNRESOLVED", "SCHEMA_RESOLUTION_FAILURE", SchemaValidationFinding.Severity.ERROR
                ));
                return;
            }
        }

        // 1. anyOf composition: at least ONE branch must match with zero findings
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            boolean anyMatched = false;
            for (Schema<?> branch : schema.getAnyOf()) {
                List<SchemaValidationFinding> branchFindings = new ArrayList<>();
                validateNode(node, branch, path, componentSchemas, branchFindings, new HashSet<>(visitedRefs), depth + 1);
                if (branchFindings.isEmpty()) {
                    anyMatched = true;
                    break;
                }
            }

            if (!anyMatched) {
                findings.add(new SchemaValidationFinding(
                        path, "anyOf (at least one branch must match)", node.getNodeType().name(), "COMPOSITION_ANYOF_VIOLATION", SchemaValidationFinding.Severity.ERROR
                ));
            }
            return;
        }

        // 2. oneOf composition: EXACTLY ONE branch must match with zero findings
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            int matchCount = 0;
            for (Schema<?> branch : schema.getOneOf()) {
                List<SchemaValidationFinding> branchFindings = new ArrayList<>();
                validateNode(node, branch, path, componentSchemas, branchFindings, new HashSet<>(visitedRefs), depth + 1);
                if (branchFindings.isEmpty()) {
                    matchCount++;
                }
            }

            if (matchCount != 1) {
                findings.add(new SchemaValidationFinding(
                        path, "oneOf (exactly one branch must match)", "Matched " + matchCount + " branches", "COMPOSITION_ONEOF_VIOLATION", SchemaValidationFinding.Severity.ERROR
                ));
            }
            return;
        }

        // 3. allOf composition: ALL branches must validate
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            for (Schema<?> branch : schema.getAllOf()) {
                validateNode(node, branch, path, componentSchemas, findings, visitedRefs, depth + 1);
            }
            if (schema.getProperties() == null && schema.getType() == null) {
                return;
            }
        }

        // Determine schema type
        String type = schema.getType();
        if (type == null) {
            if (schema.getProperties() != null) type = "object";
            else if (schema.getItems() != null) type = "array";
            else if (schema.getEnum() != null) type = "string";
            else {
                // If type is unspecified and has no structural hints, allow valid JSON node
                return;
            }
        }

        switch (type.toLowerCase()) {
            case "object" -> {
                if (!node.isObject()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: object", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                    return;
                }

                List<String> required = schema.getRequired() != null ? schema.getRequired() : Collections.emptyList();
                Map<String, Schema> properties = schema.getProperties() != null ? schema.getProperties() : Collections.emptyMap();

                for (String reqProp : required) {
                    Schema propSchema = properties.get(reqProp);
                    if (propSchema != null && Boolean.TRUE.equals(propSchema.getWriteOnly())) {
                        continue;
                    }
                    if (!node.has(reqProp)) {
                        findings.add(new SchemaValidationFinding(
                                path + "." + reqProp, "Required property in response", "MISSING", "MISSING_REQUIRED_PROPERTY", SchemaValidationFinding.Severity.ERROR
                        ));
                    }
                }

                // Validate each existing child property
                for (Map.Entry<String, Schema> propEntry : properties.entrySet()) {
                    String pName = propEntry.getKey();
                    Schema pSchema = propEntry.getValue();
                    if (node.has(pName)) {
                        validateNode(node.get(pName), pSchema, path + "." + pName, componentSchemas, findings, visitedRefs, depth + 1);
                    }
                }
            }
            case "array" -> {
                if (!node.isArray()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: array", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                    return;
                }
                if (schema.getItems() != null) {
                    int checkLimit = Math.min(node.size(), MAX_ARRAY_ITEMS_CHECK);
                    for (int i = 0; i < checkLimit; i++) {
                        validateNode(node.get(i), schema.getItems(), path + "[" + i + "]", componentSchemas, findings, visitedRefs, depth + 1);
                    }
                }
            }
            case "integer" -> {
                if (!node.isInt() && !node.isLong() && !node.isIntegralNumber()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: integer", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                } else {
                    validateNumericConstraints(node.asDouble(), schema, path, findings);
                }
            }
            case "number" -> {
                if (!node.isNumber()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: number", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                } else {
                    validateNumericConstraints(node.asDouble(), schema, path, findings);
                }
            }
            case "boolean" -> {
                if (!node.isBoolean()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: boolean", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                }
            }
            case "string" -> {
                if (!node.isTextual()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: string", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                } else {
                    validateStringConstraints(node.asText(), schema, path, findings);
                }
            }
            case "null" -> {
                if (!node.isNull()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: null", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                }
            }
        }
    }

    private boolean isNullableSchema(Schema<?> schema, Map<String, Schema> componentSchemas) {
        if (schema == null) return true;
        if (Boolean.TRUE.equals(schema.getNullable()) || "null".equalsIgnoreCase(schema.getType())) {
            return true;
        }
        if (schema.get$ref() != null && componentSchemas != null) {
            String refKey = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            Schema<?> target = componentSchemas.get(refKey);
            if (target != null && isNullableSchema(target, componentSchemas)) {
                return true;
            }
        }
        if (schema.getAnyOf() != null) {
            for (Schema<?> branch : schema.getAnyOf()) {
                if (isNullableSchema(branch, componentSchemas)) return true;
            }
        }
        if (schema.getOneOf() != null) {
            for (Schema<?> branch : schema.getOneOf()) {
                if (isNullableSchema(branch, componentSchemas)) return true;
            }
        }
        return false;
    }

    private void validateStringConstraints(String value, Schema<?> schema, String path, List<SchemaValidationFinding> findings) {
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            boolean matchesEnum = schema.getEnum().stream().anyMatch(e -> Objects.equals(String.valueOf(e), value));
            if (!matchesEnum) {
                findings.add(new SchemaValidationFinding(
                        path, "enum in " + schema.getEnum(), value, "INVALID_ENUM_VALUE", SchemaValidationFinding.Severity.ERROR
                ));
            }
        }
        if (schema.getMinLength() != null && value.length() < schema.getMinLength()) {
            findings.add(new SchemaValidationFinding(
                    path, "minLength >= " + schema.getMinLength(), "length=" + value.length(), "CONSTRAINT_VIOLATION", SchemaValidationFinding.Severity.ERROR
            ));
        }
        if (schema.getMaxLength() != null && value.length() > schema.getMaxLength()) {
            findings.add(new SchemaValidationFinding(
                    path, "maxLength <= " + schema.getMaxLength(), "length=" + value.length(), "CONSTRAINT_VIOLATION", SchemaValidationFinding.Severity.ERROR
            ));
        }
        if (schema.getPattern() != null && !value.matches(schema.getPattern())) {
            findings.add(new SchemaValidationFinding(
                    path, "pattern matches " + schema.getPattern(), value, "CONSTRAINT_VIOLATION", SchemaValidationFinding.Severity.ERROR
            ));
        }
    }

    private void validateNumericConstraints(double val, Schema<?> schema, String path, List<SchemaValidationFinding> findings) {
        if (schema.getMinimum() != null && val < schema.getMinimum().doubleValue()) {
            findings.add(new SchemaValidationFinding(
                    path, "minimum >= " + schema.getMinimum(), String.valueOf(val), "CONSTRAINT_VIOLATION", SchemaValidationFinding.Severity.ERROR
            ));
        }
        if (schema.getMaximum() != null && val > schema.getMaximum().doubleValue()) {
            findings.add(new SchemaValidationFinding(
                    path, "maximum <= " + schema.getMaximum(), String.valueOf(val), "CONSTRAINT_VIOLATION", SchemaValidationFinding.Severity.ERROR
            ));
        }
    }
}
