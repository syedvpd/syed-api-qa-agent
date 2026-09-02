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

            // 3. JSON Validity & Structure Assertion
            AssertionResult jsonAssertion = new AssertionResult();
            jsonAssertion.setId(UUID.randomUUID().toString());
            jsonAssertion.setExecution(execution);
            jsonAssertion.setAssertionType(AssertionType.JSON_SCHEMA);
            jsonAssertion.setTargetField("body");
            jsonAssertion.setExpectedValue("Valid JSON Document");

            try {
                JsonNode root = objectMapper.readTree(execution.getResponseBody());
                jsonAssertion.setActualValue(root.getNodeType().name());
                jsonAssertion.setPassed(true);
                jsonAssertion.setMessage("Response payload parsed successfully as valid JSON (" + root.getNodeType().name() + ")");
            } catch (Exception e) {
                jsonAssertion.setActualValue("Malformed JSON");
                jsonAssertion.setPassed(false);
                jsonAssertion.setMessage("Response body could not be parsed as valid JSON: " + e.getMessage());
            }
            results.add(jsonAssertion);

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
}
