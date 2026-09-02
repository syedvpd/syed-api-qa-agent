package com.syed.apiqa.assertion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.AssertionResult;
import com.syed.apiqa.domain.Execution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AssertionEngineEmptyBodyTest {

    private AssertionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AssertionEngine(new ObjectMapper());
    }

    @Test
    void shouldFailWhen200OkReturnsEmptyBody() {
        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setResponseStatus(200);
        exec.setResponseBody(""); // Empty body on 200 OK!

        List<AssertionResult> results = engine.evaluateAssertions(exec, 200, "application/json");

        boolean allPassed = results.stream().allMatch(AssertionResult::isPassed);
        assertFalse(allPassed, "Expected empty body on HTTP 200 OK to fail assertion");

        boolean foundEmptyBodyFailure = results.stream()
                .anyMatch(r -> !r.isPassed() && r.getMessage().contains("empty response body"));
        assertTrue(foundEmptyBodyFailure);
    }

    @Test
    void shouldPassWhen200OkReturnsValidJson() {
        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setResponseStatus(200);
        exec.setResponseHeaders("Content-Type: application/json");
        exec.setResponseBody("{\"id\": 1, \"status\": \"ACTIVE\"}");

        List<AssertionResult> results = engine.evaluateAssertions(exec, 200, "application/json");

        boolean allPassed = results.stream().allMatch(AssertionResult::isPassed);
        assertTrue(allPassed, "Expected valid JSON on HTTP 200 OK to pass assertions");
    }
}
