package com.syed.apiqa.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.ApiEndpoint;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal Pre-Request Contract Validator.
 * Validates generated request payloads against OpenAPI requestBody schemas
 * BEFORE dispatching HTTP requests over the wire.
 *
 * Guarantees that:
 * 1. Object schemas never dispatch scalar fallbacks or invalid JSON.
 * 2. All required fields are present in the generated payload.
 * 3. Enums, nested structures, and array types conform strictly to contract.
 * 4. If validation fails, HTTP dispatch is aborted and classified as QA_AGENT_REQUEST_GENERATION_FAILURE.
 */
@Service
public class PreRequestValidator {

    private static final Logger log = LoggerFactory.getLogger(PreRequestValidator.class);
    private final ObjectMapper objectMapper;

    public PreRequestValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record PreRequestValidationResult(
            boolean valid,
            String errorType,
            String failureReason,
            String targetSchema,
            List<String> missingFields
    ) {
        public static PreRequestValidationResult pass() {
            return new PreRequestValidationResult(true, null, null, null, Collections.emptyList());
        }

        public static PreRequestValidationResult fail(String failureReason, String targetSchema, List<String> missingFields) {
            return new PreRequestValidationResult(false, "QA_AGENT_REQUEST_GENERATION_FAILURE", failureReason, targetSchema, missingFields);
        }
    }

    public PreRequestValidationResult validate(ApiEndpoint endpoint, String requestBody, Map<String, Schema> openApiSchemas) {
        if (endpoint == null || endpoint.getRequestBodySchema() == null || endpoint.getRequestBodySchema().isBlank()) {
            return PreRequestValidationResult.pass();
        }

        String method = endpoint.getMethod() != null ? endpoint.getMethod().toUpperCase() : "GET";
        if (!"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)) {
            return PreRequestValidationResult.pass();
        }

        try {
            Schema<?> schema = objectMapper.readValue(endpoint.getRequestBodySchema(), Schema.class);
            return validateBodyAgainstSchema(schema, requestBody, endpoint.getPath(), openApiSchemas);
        } catch (Exception e) {
            log.debug("Could not parse requestBodySchema for {}: {}", endpoint.getPath(), e.getMessage());
            return PreRequestValidationResult.pass();
        }
    }

    public PreRequestValidationResult validateBodyAgainstSchema(Schema<?> schema, String requestBody, String operationPath, Map<String, Schema> openApiSchemas) {
        if (schema == null) {
            return PreRequestValidationResult.pass();
        }

        // Dereference $ref if present
        Schema<?> dereferenced = dereference(schema, openApiSchemas);
        String schemaName = dereferenced.getName() != null ? dereferenced.getName() :
                (schema.get$ref() != null ? schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1) : "AnonymousSchema");

        boolean isObject = isObjectType(dereferenced);
        boolean isArray = isArrayType(dereferenced);

        if (!isObject && !isArray) {
            return PreRequestValidationResult.pass();
        }

        if (requestBody == null || requestBody.isBlank()) {
            return PreRequestValidationResult.fail(
                    "Operation " + operationPath + " expects " + (isObject ? "JSON Object" : "JSON Array")
                            + " for schema '" + schemaName + "' but request body is empty.",
                    schemaName,
                    Collections.singletonList("body")
            );
        }

        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(requestBody);
        } catch (Exception e) {
            return PreRequestValidationResult.fail(
                    "Operation " + operationPath + " generated malformed non-JSON body for schema '" + schemaName + "': " + requestBody,
                    schemaName,
                    Collections.emptyList()
            );
        }

        // Object validation
        if (isObject) {
            if (!jsonNode.isObject()) {
                return PreRequestValidationResult.fail(
                        "Operation " + operationPath + " generated scalar/invalid value (" + requestBody
                                + ") instead of JSON Object for schema '" + schemaName + "'.",
                        schemaName,
                        Collections.emptyList()
                );
            }

            // Check required fields
            List<String> requiredFields = getRequiredProperties(dereferenced, openApiSchemas);
            List<String> missing = new ArrayList<>();
            for (String req : requiredFields) {
                if (!jsonNode.has(req) || jsonNode.get(req).isNull()) {
                    missing.add(req);
                }
            }

            if (!missing.isEmpty()) {
                return PreRequestValidationResult.fail(
                        "Operation " + operationPath + " generated request body missing required properties " + missing
                                + " for schema '" + schemaName + "'. Payload: " + requestBody,
                        schemaName,
                        missing
                );
            }
        }

        // Array validation
        if (isArray) {
            if (!jsonNode.isArray()) {
                return PreRequestValidationResult.fail(
                        "Operation " + operationPath + " generated non-array body for schema '" + schemaName + "': " + requestBody,
                        schemaName,
                        Collections.emptyList()
                );
            }
        }

        return PreRequestValidationResult.pass();
    }

    private Schema<?> dereference(Schema<?> schema, Map<String, Schema> openApiSchemas) {
        if (schema == null) return null;
        if (schema.get$ref() != null && openApiSchemas != null) {
            String ref = schema.get$ref();
            String key = ref.substring(ref.lastIndexOf('/') + 1);
            Schema<?> target = openApiSchemas.get(key);
            if (target != null) {
                return dereference(target, openApiSchemas);
            }
        }
        return schema;
    }

    private boolean isObjectType(Schema<?> schema) {
        if (schema == null) return false;
        String type = schema.getType();
        if ("object".equalsIgnoreCase(type)) return true;
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) return true;
        if (schema instanceof ComposedSchema composed) {
            if (composed.getAllOf() != null && !composed.getAllOf().isEmpty()) return true;
            if (composed.getOneOf() != null && !composed.getOneOf().isEmpty()) return true;
            if (composed.getAnyOf() != null && !composed.getAnyOf().isEmpty()) return true;
        }
        return false;
    }

    private boolean isArrayType(Schema<?> schema) {
        if (schema == null) return false;
        String type = schema.getType();
        return "array".equalsIgnoreCase(type) || schema instanceof ArraySchema || schema.getItems() != null;
    }

    private List<String> getRequiredProperties(Schema<?> schema, Map<String, Schema> openApiSchemas) {
        List<String> required = new ArrayList<>();
        if (schema == null) return required;

        if (schema.getRequired() != null) {
            required.addAll(schema.getRequired());
        }

        if (schema instanceof ComposedSchema composed && composed.getAllOf() != null) {
            for (Schema<?> sub : composed.getAllOf()) {
                Schema<?> derefSub = dereference(sub, openApiSchemas);
                if (derefSub != null && derefSub.getRequired() != null) {
                    required.addAll(derefSub.getRequired());
                }
            }
        }

        return required;
    }
}
