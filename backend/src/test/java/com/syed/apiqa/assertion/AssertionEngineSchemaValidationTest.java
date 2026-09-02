package com.syed.apiqa.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.AssertionResult;
import com.syed.apiqa.domain.AssertionType;
import com.syed.apiqa.domain.Execution;
import com.syed.apiqa.domain.TestStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AssertionEngineSchemaValidationTest {

    private AssertionEngine engine;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        engine = new AssertionEngine(objectMapper);
    }

    private TestStep createStepWithSchema(String schemaJson) {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setId(UUID.randomUUID().toString());
        endpoint.setResponseSchemas(schemaJson);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setApiEndpoint(endpoint);
        step.setMethod("GET");
        step.setPathTemplate("/users/1");
        step.setExpectedStatus(200);
        return step;
    }

    @Test
    void shouldFailWhenResponseJsonIsNonEmptyButMissingRequiredSchemaFields() {
        // Schema requires "id" (integer) and "email" (string)
        String responseSchemasJson = "{"
                + "\"200\": {"
                + "  \"description\": \"Success\","
                + "  \"schema\": {"
                + "    \"type\": \"object\","
                + "    \"required\": [\"id\", \"email\"],"
                + "    \"properties\": {"
                + "      \"id\": { \"type\": \"integer\" },"
                + "      \"email\": { \"type\": \"string\" }"
                + "    }"
                + "  }"
                + "}"
                + "}";

        TestStep step = createStepWithSchema(responseSchemasJson);

        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setTestStep(step);
        exec.setResponseStatus(200);
        exec.setResponseHeaders("Content-Type: application/json");
        // Valid JSON, non-empty, but WRONG shape! (Old code passed this as valid JSON)
        exec.setResponseBody("{\"foo\": \"bar\"}");

        List<AssertionResult> results = engine.evaluateAssertions(exec, 200, "application/json");

        boolean allPassed = results.stream().allMatch(AssertionResult::isPassed);
        assertFalse(allPassed, "Wrong-shaped JSON missing required fields must fail schema assertion");

        // Verify specific failure messages
        List<AssertionResult> schemaFailures = results.stream()
                .filter(r -> !r.isPassed() && r.getAssertionType() == AssertionType.JSON_SCHEMA)
                .toList();

        assertFalse(schemaFailures.isEmpty(), "Expected JSON_SCHEMA assertion failures");
        assertTrue(schemaFailures.stream().anyMatch(f ->
                f.getMessage().contains("Expected field 'id' (integer) — missing from response")),
                "Expected failure message for missing id field");
        assertTrue(schemaFailures.stream().anyMatch(f ->
                f.getMessage().contains("Expected field 'email' (string) — missing from response")),
                "Expected failure message for missing email field");
    }

    @Test
    void shouldFailWhenFieldHasWrongType() {
        String responseSchemasJson = "{"
                + "\"200\": {"
                + "  \"schema\": {"
                + "    \"type\": \"object\","
                + "    \"required\": [\"id\", \"email\"],"
                + "    \"properties\": {"
                + "      \"id\": { \"type\": \"integer\" },"
                + "      \"email\": { \"type\": \"string\" }"
                + "    }"
                + "  }"
                + "}"
                + "}";

        TestStep step = createStepWithSchema(responseSchemasJson);

        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setTestStep(step);
        exec.setResponseStatus(200);
        exec.setResponseHeaders("Content-Type: application/json");
        // "id" is string instead of integer
        exec.setResponseBody("{\"id\": \"not-an-integer\", \"email\": \"user@example.com\"}");

        List<AssertionResult> results = engine.evaluateAssertions(exec, 200, "application/json");

        boolean allPassed = results.stream().allMatch(AssertionResult::isPassed);
        assertFalse(allPassed, "Type mismatch on field must fail schema assertion");

        boolean typeFailureFound = results.stream().anyMatch(f ->
                !f.isPassed() && f.getMessage().contains("Field 'id' has invalid type: expected integer, got string"));
        assertTrue(typeFailureFound, "Expected specific field type mismatch failure message");
    }

    @Test
    void shouldPassWhenResponseMatchesRequiredFieldsAndTypes() {
        String responseSchemasJson = "{"
                + "\"200\": {"
                + "  \"schema\": {"
                + "    \"type\": \"object\","
                + "    \"required\": [\"id\", \"email\"],"
                + "    \"properties\": {"
                + "      \"id\": { \"type\": \"integer\" },"
                + "      \"email\": { \"type\": \"string\" }"
                + "    }"
                + "  }"
                + "}"
                + "}";

        TestStep step = createStepWithSchema(responseSchemasJson);

        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setTestStep(step);
        exec.setResponseStatus(200);
        exec.setResponseHeaders("Content-Type: application/json");
        exec.setResponseBody("{\"id\": 101, \"email\": \"user@example.com\"}");

        List<AssertionResult> results = engine.evaluateAssertions(exec, 200, "application/json");

        boolean allPassed = results.stream().allMatch(AssertionResult::isPassed);
        assertTrue(allPassed, "Matching JSON response must pass all schema assertions");

        boolean schemaPassedFound = results.stream().anyMatch(r ->
                r.isPassed() && r.getAssertionType() == AssertionType.JSON_SCHEMA &&
                r.getMessage().contains("conforms to OpenAPI response schema"));
        assertTrue(schemaPassedFound);
    }
}
