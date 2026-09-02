package com.syed.apiqa.contract.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.syed.apiqa.contract.schema.SchemaContext;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal Response Schema Validator.
 * Validates actual response JSON AST against declared contract OpenAPI response schemas,
 * producing structured findings without superficial pass/fail collapses.
 */
@Service
public class ResponseSchemaValidator {

    public List<SchemaValidationFinding> validate(JsonNode actualBody, Schema<?> expectedSchema, Map<String, Schema> componentSchemas) {
        List<SchemaValidationFinding> findings = new ArrayList<>();
        if (expectedSchema == null || actualBody == null || actualBody.isNull()) {
            return findings;
        }

        validateNode(actualBody, expectedSchema, "$", componentSchemas, findings);
        return findings;
    }

    private void validateNode(JsonNode node, Schema<?> schema, String path, Map<String, Schema> componentSchemas, List<SchemaValidationFinding> findings) {
        if (schema == null || node == null) return;

        // Dereference $ref
        if (schema.get$ref() != null && componentSchemas != null) {
            String refKey = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            Schema<?> target = componentSchemas.get(refKey);
            if (target != null) {
                validateNode(node, target, path, componentSchemas, findings);
                return;
            }
        }

        String type = schema.getType();
        if (type == null) {
            if (schema.getProperties() != null) type = "object";
            else if (schema.getItems() != null) type = "array";
            else type = "string";
        }

        switch (type.toLowerCase()) {
            case "object" -> {
                if (!node.isObject()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: object", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                    return;
                }

                // Check required properties (excluding writeOnly)
                List<String> required = schema.getRequired() != null ? schema.getRequired() : Collections.emptyList();
                Map<String, Schema> properties = schema.getProperties() != null ? schema.getProperties() : Collections.emptyMap();

                for (String reqProp : required) {
                    Schema propSchema = properties.get(reqProp);
                    // writeOnly properties are not expected in response
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
                        validateNode(node.get(pName), pSchema, path + "." + pName, componentSchemas, findings);
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
                    for (int i = 0; i < Math.min(node.size(), 10); i++) {
                        validateNode(node.get(i), schema.getItems(), path + "[" + i + "]", componentSchemas, findings);
                    }
                }
            }
            case "integer" -> {
                if (!node.isInt() && !node.isLong()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: integer", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
                }
            }
            case "number" -> {
                if (!node.isNumber()) {
                    findings.add(new SchemaValidationFinding(
                            path, "type: number", node.getNodeType().name(), "TYPE_MISMATCH", SchemaValidationFinding.Severity.ERROR
                    ));
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
                }
            }
        }
    }
}
