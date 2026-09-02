package com.syed.apiqa.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.AssertionResult;
import com.syed.apiqa.domain.AssertionType;
import com.syed.apiqa.domain.Execution;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic Assertion Engine.
 * Validates HTTP status codes, content-types, required fields, and response schemas.
 * Inverts expected status for negative tests (e.g. expecting 404 after delete).
 */
@Service
public class AssertionEngine {

    private final ObjectMapper objectMapper;

    public AssertionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<AssertionResult> evaluateAssertions(Execution execution, Integer expectedStatus, String expectedContentType) {
        List<AssertionResult> results = new ArrayList<>();

        // 1. Status Code Assertion
        if (expectedStatus != null) {
            AssertionResult statusAssertion = new AssertionResult();
            statusAssertion.setId(UUID.randomUUID().toString());
            statusAssertion.setExecution(execution);
            statusAssertion.setAssertionType(AssertionType.STATUS_CODE);
            statusAssertion.setTargetField("status");
            statusAssertion.setExpectedValue(String.valueOf(expectedStatus));
            statusAssertion.setActualValue(String.valueOf(execution.getResponseStatus()));

            boolean passed;
            String message;
            int actual = execution.getResponseStatus() != null ? execution.getResponseStatus() : 0;

            if (expectedStatus == 400 || expectedStatus == 422) {
                // Negative testing: expect server rejection (400 Bad Request or 422 Unprocessable)
                if (actual == 400 || actual == 422) {
                    passed = true;
                    message = "Validation rejection verified: Server returned HTTP " + actual + " rejecting invalid input.";
                } else if (actual >= 500) {
                    passed = false;
                    message = "Server crash on negative input: Unhandled HTTP " + actual + " Internal Server Error!";
                } else if (actual >= 200 && actual < 300) {
                    passed = false;
                    message = "Validation bypass: Server accepted invalid payload with HTTP " + actual;
                } else {
                    passed = false;
                    message = "Expected validation rejection (400/422) but received HTTP " + actual;
                }
            } else if (expectedStatus == 304) {
                // Conditional Request: ETag / If-None-Match 304 Not Modified check
                passed = (actual == 304 || actual == 200);
                message = actual == 304
                        ? "HTTP 304 Not Modified verified for conditional If-None-Match request"
                        : "Conditional request returned HTTP " + actual;
            } else if (expectedStatus == 200 || expectedStatus == 201 || expectedStatus == 204) {
                passed = actual >= 200 && actual < 300;
                message = passed
                        ? "HTTP Status " + actual + " matches successful contract expectation (" + expectedStatus + ")"
                        : "Expected success (" + expectedStatus + ") but received HTTP " + actual;
            } else {
                // Exact status match (e.g. 404 after delete, or 401 for auth check)
                passed = (expectedStatus == actual);
                message = passed
                        ? "HTTP Status " + actual + " matches expected status (" + expectedStatus + ")"
                        : "Status mismatch: Expected HTTP " + expectedStatus + " but received " + actual;
            }

            statusAssertion.setPassed(passed);
            statusAssertion.setMessage(message);
            results.add(statusAssertion);
        }

        // 2. Body Existence Assertion (for HTTP 200 / 201 expecting payload)
        if (execution.getResponseStatus() != null &&
                (execution.getResponseStatus() == 200 || execution.getResponseStatus() == 201) &&
                (execution.getResponseBody() == null || execution.getResponseBody().isBlank())) {
            AssertionResult emptyBodyAssertion = new AssertionResult();
            emptyBodyAssertion.setId(UUID.randomUUID().toString());
            emptyBodyAssertion.setExecution(execution);
            emptyBodyAssertion.setAssertionType(AssertionType.JSON_SCHEMA);
            emptyBodyAssertion.setTargetField("body");
            emptyBodyAssertion.setExpectedValue("Non-empty JSON entity representation");
            emptyBodyAssertion.setActualValue("Empty/Missing body");
            emptyBodyAssertion.setPassed(false);
            emptyBodyAssertion.setMessage("Contract violation: Endpoint returned HTTP " + execution.getResponseStatus() +
                    " with an empty response body when a valid entity representation was expected.");
            results.add(emptyBodyAssertion);
        }

        // 3. Content-Type & JSON Schema Assertion (for successful responses with body)
        if (execution.getResponseBody() != null && !execution.getResponseBody().isBlank() &&
                execution.getResponseStatus() != null && execution.getResponseStatus() >= 200 && execution.getResponseStatus() < 300) {

            AssertionResult ctAssertion = new AssertionResult();
            ctAssertion.setId(UUID.randomUUID().toString());
            ctAssertion.setExecution(execution);
            ctAssertion.setAssertionType(AssertionType.HEADER_VALUE);
            ctAssertion.setTargetField("Content-Type");

            String expectedCt = expectedContentType != null ? expectedContentType : "application/json";
            ctAssertion.setExpectedValue(expectedCt);

            String actualHeaders = execution.getResponseHeaders();
            boolean isJson = actualHeaders != null && (actualHeaders.toLowerCase().contains("application/json") || actualHeaders.toLowerCase().contains("application/problem+json"));
            ctAssertion.setActualValue(isJson ? "application/json" : "other");
            ctAssertion.setPassed(isJson);
            ctAssertion.setMessage(isJson
                    ? "Content-Type contains valid JSON representation"
                    : "Response body was present but Content-Type was not application/json");
            results.add(ctAssertion);

            // 3. OpenAPI Schema & JSON Field Validation
            JsonNode root;
            try {
                root = objectMapper.readTree(execution.getResponseBody());
            } catch (Exception e) {
                AssertionResult jsonAssertion = new AssertionResult();
                jsonAssertion.setId(UUID.randomUUID().toString());
                jsonAssertion.setExecution(execution);
                jsonAssertion.setAssertionType(AssertionType.JSON_SCHEMA);
                jsonAssertion.setTargetField("body");
                jsonAssertion.setExpectedValue("Valid JSON Document");
                jsonAssertion.setActualValue("Malformed JSON");
                jsonAssertion.setPassed(false);
                jsonAssertion.setMessage("Response body could not be parsed as valid JSON: " + e.getMessage());
                results.add(jsonAssertion);
                return results;
            }

            validateOpenApiSchema(execution, root, expectedStatus, results);

            // 4. Response Header Validation (ETag / Location / Correlation ID)
            if (actualHeaders != null) {
                String headersLower = actualHeaders.toLowerCase();
                if (headersLower.contains("etag")) {
                    AssertionResult etagAssertion = new AssertionResult();
                    etagAssertion.setId(UUID.randomUUID().toString());
                    etagAssertion.setExecution(execution);
                    etagAssertion.setAssertionType(AssertionType.HEADER_VALUE);
                    etagAssertion.setTargetField("ETag");
                    etagAssertion.setExpectedValue("Present");
                    etagAssertion.setActualValue("ETag Present");
                    etagAssertion.setPassed(true);
                    etagAssertion.setMessage("ETag header present for cache revalidation");
                    results.add(etagAssertion);
                }
            }
        }

        return results;
    }

    private void validateOpenApiSchema(Execution execution, JsonNode root, Integer expectedStatus, List<AssertionResult> results) {
        String responseSchemasJson = null;
        try {
            if (execution.getTestStep() != null && execution.getTestStep().getApiEndpoint() != null) {
                responseSchemasJson = execution.getTestStep().getApiEndpoint().getResponseSchemas();
            }
        } catch (Exception ignored) {}

        if (responseSchemasJson == null || responseSchemasJson.isBlank()) {
            addValidJsonFallback(execution, root, results);
            return;
        }

        try {
            JsonNode allSchemas = objectMapper.readTree(responseSchemasJson);
            int actualStatus = execution.getResponseStatus() != null ? execution.getResponseStatus() : (expectedStatus != null ? expectedStatus : 200);
            String statusKey = String.valueOf(actualStatus);

            JsonNode statusObj = allSchemas.get(statusKey);
            if (statusObj == null) statusObj = allSchemas.get("default");
            if (statusObj == null) statusObj = allSchemas.get("2XX");

            if (statusObj == null) {
                addValidJsonFallback(execution, root, results);
                return;
            }

            JsonNode schemaNode = null;
            if (statusObj.has("schema")) {
                schemaNode = statusObj.get("schema");
            } else if (statusObj.has("type") || statusObj.has("properties") || statusObj.has("required") || statusObj.has("items")) {
                schemaNode = statusObj;
            }

            if (schemaNode == null || schemaNode.isNull() || schemaNode.isEmpty()) {
                addValidJsonFallback(execution, root, results);
                return;
            }

            int failureCountBefore = results.size();

            // 1. Root Type Validation
            if (schemaNode.has("type")) {
                String expectedRootType = schemaNode.get("type").asText().toLowerCase();
                if (!checkNodeType(root, expectedRootType)) {
                    AssertionResult res = new AssertionResult();
                    res.setId(UUID.randomUUID().toString());
                    res.setExecution(execution);
                    res.setAssertionType(AssertionType.JSON_SCHEMA);
                    res.setTargetField("body");
                    res.setExpectedValue("Type: " + expectedRootType);
                    res.setActualValue(root.getNodeType().name().toLowerCase());
                    res.setPassed(false);
                    res.setMessage("Response root type mismatch: expected " + expectedRootType + ", got " + root.getNodeType().name().toLowerCase());
                    results.add(res);
                    return;
                }
            }

            // 2. Object Schema Validation
            if (root.isObject()) {
                // Check Required Fields
                if (schemaNode.has("required") && schemaNode.get("required").isArray()) {
                    for (JsonNode fieldElem : schemaNode.get("required")) {
                        String requiredField = fieldElem.asText();
                        if (!root.has(requiredField) || root.get(requiredField).isNull()) {
                            String fieldType = "any";
                            if (schemaNode.has("properties") && schemaNode.get("properties").has(requiredField)) {
                                JsonNode propDef = schemaNode.get("properties").get(requiredField);
                                if (propDef.has("type")) fieldType = propDef.get("type").asText();
                            }
                            AssertionResult res = new AssertionResult();
                            res.setId(UUID.randomUUID().toString());
                            res.setExecution(execution);
                            res.setAssertionType(AssertionType.JSON_SCHEMA);
                            res.setTargetField("body." + requiredField);
                            res.setExpectedValue("Present (" + fieldType + ")");
                            res.setActualValue("missing");
                            res.setPassed(false);
                            res.setMessage("Expected field '" + requiredField + "' (" + fieldType + ") — missing from response");
                            results.add(res);
                        }
                    }
                }

                // Check Properties Types
                if (schemaNode.has("properties") && schemaNode.get("properties").isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> propIt = schemaNode.get("properties").fields();
                    while (propIt.hasNext()) {
                        Map.Entry<String, JsonNode> prop = propIt.next();
                        String propName = prop.getKey();
                        JsonNode propDef = prop.getValue();

                        if (root.has(propName) && !root.get(propName).isNull()) {
                            JsonNode actualVal = root.get(propName);
                            if (propDef.has("type")) {
                                String expectedPropType = propDef.get("type").asText().toLowerCase();
                                if (!checkNodeType(actualVal, expectedPropType)) {
                                    AssertionResult res = new AssertionResult();
                                    res.setId(UUID.randomUUID().toString());
                                    res.setExecution(execution);
                                    res.setAssertionType(AssertionType.JSON_SCHEMA);
                                    res.setTargetField("body." + propName);
                                    res.setExpectedValue(expectedPropType);
                                    res.setActualValue(actualVal.getNodeType().name().toLowerCase());
                                    res.setPassed(false);
                                    res.setMessage("Field '" + propName + "' has invalid type: expected " + expectedPropType + ", got " + actualVal.getNodeType().name().toLowerCase());
                                    results.add(res);
                                }
                            }
                        }
                    }
                }
            }

            // 3. Array Schema Validation
            if (root.isArray() && schemaNode.has("items")) {
                JsonNode itemSchema = schemaNode.get("items");
                int itemsToCheck = Math.min(root.size(), 10);
                for (int i = 0; i < itemsToCheck; i++) {
                    JsonNode itemNode = root.get(i);
                    if (itemSchema.has("type")) {
                        String expectedItemType = itemSchema.get("type").asText().toLowerCase();
                        if (!checkNodeType(itemNode, expectedItemType)) {
                            AssertionResult res = new AssertionResult();
                            res.setId(UUID.randomUUID().toString());
                            res.setExecution(execution);
                            res.setAssertionType(AssertionType.JSON_SCHEMA);
                            res.setTargetField("body[" + i + "]");
                            res.setExpectedValue(expectedItemType);
                            res.setActualValue(itemNode.getNodeType().name().toLowerCase());
                            res.setPassed(false);
                            res.setMessage("Array item at index " + i + " has invalid type: expected " + expectedItemType + ", got " + itemNode.getNodeType().name().toLowerCase());
                            results.add(res);
                            break;
                        }
                    }
                    if (itemNode.isObject() && itemSchema.has("required") && itemSchema.get("required").isArray()) {
                        for (JsonNode req : itemSchema.get("required")) {
                            String reqField = req.asText();
                            if (!itemNode.has(reqField) || itemNode.get(reqField).isNull()) {
                                AssertionResult res = new AssertionResult();
                                res.setId(UUID.randomUUID().toString());
                                res.setExecution(execution);
                                res.setAssertionType(AssertionType.JSON_SCHEMA);
                                res.setTargetField("body[" + i + "]." + reqField);
                                res.setExpectedValue("Present");
                                res.setActualValue("missing");
                                res.setPassed(false);
                                res.setMessage("Array item at index " + i + " missing required field '" + reqField + "'");
                                results.add(res);
                                break;
                            }
                        }
                    }
                }
            }

            // If no schema violations were added, record successful schema match
            if (results.size() == failureCountBefore) {
                AssertionResult schemaSuccess = new AssertionResult();
                schemaSuccess.setId(UUID.randomUUID().toString());
                schemaSuccess.setExecution(execution);
                schemaSuccess.setAssertionType(AssertionType.JSON_SCHEMA);
                schemaSuccess.setTargetField("body");
                schemaSuccess.setExpectedValue("Valid OpenAPI Schema (" + statusKey + ")");
                schemaSuccess.setActualValue("Valid");
                schemaSuccess.setPassed(true);
                schemaSuccess.setMessage("Response body conforms to OpenAPI response schema for status " + statusKey);
                results.add(schemaSuccess);
            }

        } catch (Exception e) {
            AssertionResult errAssertion = new AssertionResult();
            errAssertion.setId(UUID.randomUUID().toString());
            errAssertion.setExecution(execution);
            errAssertion.setAssertionType(AssertionType.JSON_SCHEMA);
            errAssertion.setTargetField("body");
            errAssertion.setExpectedValue("Valid OpenAPI Schema");
            errAssertion.setActualValue("Schema Evaluation Error");
            errAssertion.setPassed(false);
            errAssertion.setMessage("Failed to evaluate OpenAPI response schema: " + e.getMessage());
            results.add(errAssertion);
        }
    }

    private boolean checkNodeType(JsonNode node, String expectedType) {
        if (node == null || expectedType == null) return false;
        switch (expectedType.toLowerCase()) {
            case "string":
                return node.isTextual();
            case "integer":
                return node.isInt() || node.isLong() || node.isIntegralNumber();
            case "number":
                return node.isNumber();
            case "boolean":
                return node.isBoolean();
            case "object":
                return node.isObject();
            case "array":
                return node.isArray();
            default:
                return true;
        }
    }

    private void addValidJsonFallback(Execution execution, JsonNode root, List<AssertionResult> results) {
        AssertionResult jsonAssertion = new AssertionResult();
        jsonAssertion.setId(UUID.randomUUID().toString());
        jsonAssertion.setExecution(execution);
        jsonAssertion.setAssertionType(AssertionType.JSON_SCHEMA);
        jsonAssertion.setTargetField("body");
        jsonAssertion.setExpectedValue("Valid JSON Document");
        jsonAssertion.setActualValue(root.getNodeType().name());
        jsonAssertion.setPassed(true);
        jsonAssertion.setMessage("Response payload parsed successfully as valid JSON (" + root.getNodeType().name() + ")");
        results.add(jsonAssertion);
    }
}
