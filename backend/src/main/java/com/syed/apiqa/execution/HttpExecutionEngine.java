package com.syed.apiqa.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.assertion.AssertionEngine;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.AssertionResultRepository;
import com.syed.apiqa.persistence.CapturedVariableRepository;
import com.syed.apiqa.persistence.ExecutionRepository;
import com.syed.apiqa.safety.SecretMasker;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Production-grade HTTP Execution Engine built on modern Java 21 java.net.http.HttpClient.
 * Natively supports all standard HTTP verbs including PATCH, GET, POST, PUT, DELETE,
 * variable substitution, SSRF validation, nanosecond latency measurement,
 * retry safety, rate limiting (429), secret redaction, and variable capture.
 */
@Service
public class HttpExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(HttpExecutionEngine.class);

    private final SsrfProtectionGuard ssrfGuard;
    private final SecretMasker secretMasker;
    private final AssertionEngine assertionEngine;
    private final ExecutionRepository executionRepository;
    private final AssertionResultRepository assertionResultRepository;
    private final CapturedVariableRepository capturedVariableRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${syed.safety.default-timeout-seconds:15}")
    private int defaultTimeoutSeconds;

    @Value("${syed.safety.max-response-size-bytes:2097152}")
    private int maxResponseSizeBytes;

    public HttpExecutionEngine(SsrfProtectionGuard ssrfGuard,
                               SecretMasker secretMasker,
                               AssertionEngine assertionEngine,
                               ExecutionRepository executionRepository,
                               AssertionResultRepository assertionResultRepository,
                               CapturedVariableRepository capturedVariableRepository,
                               ObjectMapper objectMapper) {
        this.ssrfGuard = ssrfGuard;
        this.secretMasker = secretMasker;
        this.assertionEngine = assertionEngine;
        this.executionRepository = executionRepository;
        this.assertionResultRepository = assertionResultRepository;
        this.capturedVariableRepository = capturedVariableRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static class StepExecutionOutcome {
        private final StepStatus finalStatus;
        private final Execution execution;
        private final List<AssertionResult> assertions;
        private final String failureMessage;

        public StepExecutionOutcome(StepStatus finalStatus, Execution execution,
                                    List<AssertionResult> assertions, String failureMessage) {
            this.finalStatus = finalStatus;
            this.execution = execution;
            this.assertions = assertions;
            this.failureMessage = failureMessage;
        }

        public StepStatus getFinalStatus() { return finalStatus; }
        public Execution getExecution() { return execution; }
        public List<AssertionResult> getAssertions() { return assertions; }
        public String getFailureMessage() { return failureMessage; }
    }

    public StepExecutionOutcome executeStep(TestStep step,
                                            String baseUrl,
                                            ExecutionContext context,
                                            EnvironmentType envType,
                                            String authType,
                                            String authCredentials) {

        // 1. Resolve Variables in Path & URL
        ExecutionContext.ResolutionResult urlResolution = context.resolve(step.getPathTemplate());
        if (!urlResolution.isFullyResolved()) {
            step.setStatus(StepStatus.BLOCKED);
            step.setFailureReason("Missing required context variable: {{" + urlResolution.getMissingVariable() + "}}");
            return new StepExecutionOutcome(StepStatus.BLOCKED, null, Collections.emptyList(), step.getFailureReason());
        }

        String relativePath = urlResolution.getResolvedContent();
        String fullUrl = baseUrl + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        step.setResolvedUrl(fullUrl);

        // 2. Resolve Variables in Request Body (if present)
        String requestBody = step.getRequestBody();
        if (requestBody != null) {
            ExecutionContext.ResolutionResult bodyResolution = context.resolve(requestBody);
            requestBody = bodyResolution.getResolvedContent();
        }

        // 3. SSRF & Safety Pre-Check
        try {
            ssrfGuard.validateTargetUrl(fullUrl);
        } catch (SecurityException | IllegalArgumentException e) {
            step.setStatus(StepStatus.FAILED);
            step.setFailureReason("SSRF Guard violation: " + e.getMessage());
            return new StepExecutionOutcome(StepStatus.FAILED, null, Collections.emptyList(), step.getFailureReason());
        }

        // 4. Production Method Policy Check
        if (envType == EnvironmentType.PRODUCTION && "DELETE".equalsIgnoreCase(step.getMethod())) {
            step.setStatus(StepStatus.SKIPPED);
            step.setFailureReason("HTTP DELETE is disabled by default in PRODUCTION mode.");
            return new StepExecutionOutcome(StepStatus.SKIPPED, null, Collections.emptyList(), step.getFailureReason());
        }

        // 5. Execute HTTP Request with Retry & Timeout Safety
        return dispatchWithSafety(step, fullUrl, requestBody, context, authType, authCredentials);
    }

    private StepExecutionOutcome dispatchWithSafety(TestStep step,
                                                    String targetUrl,
                                                    String requestBody,
                                                    ExecutionContext context,
                                                    String authType,
                                                    String authCredentials) {

        String method = step.getMethod().toUpperCase();
        boolean isRetryable = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        int maxAttempts = isRetryable ? 2 : 1;
        int attempt = 0;

        Execution execution = new Execution();
        execution.setId(UUID.randomUUID().toString());
        execution.setTestStep(step);
        execution.setMethod(method);
        execution.setRequestUrl(targetUrl);
        execution.setRequestBody(secretMasker.maskBody(requestBody));

        OffsetDateTime startedAt = OffsetDateTime.now();
        execution.setStartedAt(startedAt);

        while (attempt < maxAttempts) {
            attempt++;
            long startNanos = System.nanoTime();

            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofSeconds(defaultTimeoutSeconds))
                        .header("User-Agent", "Syed-API-QA-Agent/1.0")
                        .header("Accept", "application/json, */*");

                if (requestBody != null && !requestBody.isBlank()) {
                    reqBuilder.header("Content-Type", "application/json; charset=UTF-8");
                }

                // Inject Authentication
                applyAuth(reqBuilder, authType, authCredentials);

                // Apply Custom Headers (e.g. If-None-Match, Idempotency-Key)
                if (step.getRequestHeaders() != null && !step.getRequestHeaders().isBlank()) {
                    applyCustomHeaders(reqBuilder, step.getRequestHeaders(), context);
                }

                // Body Publisher
                HttpRequest.BodyPublisher bodyPublisher = (requestBody != null && !requestBody.isBlank())
                        ? HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody();

                if ("GET".equals(method)) {
                    reqBuilder.GET();
                } else if ("DELETE".equals(method)) {
                    reqBuilder.DELETE();
                } else {
                    // Modern Java 21 HttpClient natively supports PATCH, POST, PUT, etc.
                    reqBuilder.method(method, bodyPublisher);
                }

                HttpRequest httpRequest = reqBuilder.build();

                // Mask and record request headers
                Map<String, List<String>> rawReqHeaders = httpRequest.headers().map();
                execution.setRequestHeaders(objectMapper.writeValueAsString(secretMasker.maskHeaders(rawReqHeaders)));

                // Send request and capture timing
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long elapsedNanos = System.nanoTime() - startNanos;
                long latencyMs = Math.max(1, elapsedNanos / 1_000_000);
                execution.setLatencyMs(latencyMs);
                execution.setResponseStatus(response.statusCode());

                // Record Response Headers (Sanitized)
                Map<String, List<String>> rawRespHeaders = response.headers().map();
                execution.setResponseHeaders(objectMapper.writeValueAsString(secretMasker.maskHeaders(rawRespHeaders)));

                // Handle 429 Too Many Requests (Rate Limiting)
                if (response.statusCode() == 429 && attempt < maxAttempts) {
                    Optional<String> retryAfterOpt = response.headers().firstValue("Retry-After");
                    int sleepSec = 1;
                    if (retryAfterOpt.isPresent()) {
                        try { sleepSec = Math.min(3, Integer.parseInt(retryAfterOpt.get().trim())); } catch (Exception ignored) {}
                    }
                    Thread.sleep(sleepSec * 1000L);
                    continue;
                }

                // Response body (bounded)
                String rawBody = response.body();
                if (rawBody != null && rawBody.length() > maxResponseSizeBytes) {
                    rawBody = rawBody.substring(0, maxResponseSizeBytes) + "\n[RESPONSE TRUNCATED - EXCEEDED 2MB LIMIT]";
                }
                execution.setResponseBody(secretMasker.maskBody(rawBody));
                execution.setCompletedAt(OffsetDateTime.now());

                // Evaluate Assertions
                List<AssertionResult> assertions = assertionEngine.evaluateAssertions(execution, step.getExpectedStatus(), "application/json");
                boolean allPassed = assertions.stream().allMatch(AssertionResult::isPassed);

                StepStatus finalStatus;
                if (response.statusCode() == 429) {
                    finalStatus = StepStatus.RATE_LIMITED;
                } else if (response.statusCode() == 401) {
                    finalStatus = StepStatus.AUTHENTICATION_ERROR;
                } else if (response.statusCode() == 403) {
                    finalStatus = StepStatus.AUTHORIZATION_ERROR;
                } else if (allPassed) {
                    finalStatus = StepStatus.PASSED;
                } else {
                    finalStatus = StepStatus.FAILED;
                }
                execution.setStatus(finalStatus);

                // Extract ETag header for conditional request testing
                Optional<String> etagHeader = response.headers().firstValue("ETag");
                if (etagHeader.isEmpty()) etagHeader = response.headers().firstValue("etag");
                etagHeader.ifPresent(tag -> {
                    context.setVariable("etag", tag);
                    String entity = extractEntityPrefix(step.getPathTemplate());
                    context.setVariable(entity + ".etag", tag);
                });

                // Extract Variables from successful response payload
                if (finalStatus == StepStatus.PASSED && rawBody != null && !rawBody.isBlank()) {
                    extractAndStoreVariables(rawBody, step, context, execution);
                }

                // Persist execution evidence and assertion results
                executionRepository.save(execution);
                for (AssertionResult ar : assertions) {
                    assertionResultRepository.save(ar);
                }

                step.setStatus(finalStatus);
                return new StepExecutionOutcome(finalStatus, execution, assertions, null);

            } catch (HttpTimeoutException e) {
                long latencyMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
                execution.setLatencyMs(latencyMs);
                execution.setCompletedAt(OffsetDateTime.now());

                if ("POST".equals(method)) {
                    // Strict Rule: POST timeout -> OUTCOME UNCERTAIN, RETRY SUPPRESSED
                    execution.setStatus(StepStatus.TIMEOUT);
                    execution.setErrorType("POST_TIMEOUT");
                    execution.setErrorDetails("POST request timed out. Automatic retry suppressed to avoid duplicate resource creation.");
                    executionRepository.save(execution);

                    step.setStatus(StepStatus.TIMEOUT);
                    step.setFailureReason("POST timed out: Outcome uncertain, retry suppressed for safety.");
                    return new StepExecutionOutcome(StepStatus.TIMEOUT, execution, Collections.emptyList(), step.getFailureReason());
                }

                if (attempt >= maxAttempts) {
                    execution.setStatus(StepStatus.TIMEOUT);
                    execution.setErrorType("TIMEOUT");
                    execution.setErrorDetails("Request timed out after " + attempt + " attempts.");
                    executionRepository.save(execution);

                    step.setStatus(StepStatus.TIMEOUT);
                    step.setFailureReason("Timeout after " + defaultTimeoutSeconds + "s");
                    return new StepExecutionOutcome(StepStatus.TIMEOUT, execution, Collections.emptyList(), step.getFailureReason());
                }

            } catch (Exception e) {
                log.error("HttpExecutionEngine exception dispatching to {}: {}", targetUrl, e.getMessage(), e);
                long latencyMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
                execution.setLatencyMs(latencyMs);
                execution.setCompletedAt(OffsetDateTime.now());
                execution.setStatus(StepStatus.NETWORK_ERROR);
                execution.setErrorType("NETWORK_ERROR");
                execution.setErrorDetails(e.getMessage());
                executionRepository.save(execution);

                step.setStatus(StepStatus.NETWORK_ERROR);
                step.setFailureReason("Network dispatch error: " + e.getMessage());
                return new StepExecutionOutcome(StepStatus.NETWORK_ERROR, execution, Collections.emptyList(), step.getFailureReason());
            }
        }

        step.setStatus(StepStatus.FAILED);
        return new StepExecutionOutcome(StepStatus.FAILED, execution, Collections.emptyList(), "Execution failed after maximum retries");
    }

    private void applyAuth(HttpRequest.Builder reqBuilder, String authType, String credentials) {
        if (authType == null || credentials == null || credentials.isBlank() || "NONE".equalsIgnoreCase(authType)) {
            return;
        }

        switch (authType.toUpperCase()) {
            case "BEARER":
                reqBuilder.header("Authorization", "Bearer " + credentials.trim());
                break;
            case "API_KEY":
                reqBuilder.header("X-Api-Key", credentials.trim());
                break;
            case "BASIC":
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + encoded);
                break;
        }
    }

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "secret", "api_key", "apikey", "access_token",
            "cookie", "authorization", "refresh_token", "session", "csrf",
            "private_key", "client_secret", "credentials", "auth_token");

    private void extractAndStoreVariables(String jsonBody, TestStep step, ExecutionContext context, Execution execution) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            String entity = extractEntityPrefix(step.getPathTemplate());

            if (root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String key = field.getKey();
                    JsonNode val = field.getValue();

                    // Skip sensitive fields to prevent storing credentials in DB
                    if (SENSITIVE_KEYS.contains(key.toLowerCase())) continue;

                    if (val.isValueNode() && !val.isNull()) {
                        String valueStr = val.asText();

                        // Store scoped variable (e.g. user.id = 123)
                        String scopedName = entity + "." + key;
                        context.setVariable(scopedName, valueStr);

                        // If key is "id" or "uuid", also store bare variable for shorthand access
                        if ("id".equalsIgnoreCase(key) || "uuid".equalsIgnoreCase(key)) {
                            context.setVariable(key, valueStr);
                            context.setVariable(entity + "_id", valueStr);
                        }

                        // Persist to database
                        CapturedVariable cv = new CapturedVariable();
                        cv.setId(UUID.randomUUID().toString());
                        cv.setTestRun(step.getTestCase().getTestRun());
                        cv.setExecution(execution);
                        cv.setVariableName(scopedName);
                        cv.setVariableValue(valueStr);
                        capturedVariableRepository.save(cv);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String extractEntityPrefix(String path) {
        if (path == null) return "entity";
        String[] parts = path.split("/");
        for (String p : parts) {
            if (!p.isBlank() && !p.startsWith("{") && !p.equalsIgnoreCase("api") &&
                    !p.equalsIgnoreCase("v1") && !p.equalsIgnoreCase("v2")) {
                return p.toLowerCase();
            }
        }
        return "entity";
    }

    private void applyCustomHeaders(HttpRequest.Builder reqBuilder, String customHeaders, ExecutionContext context) {
        if (customHeaders == null || customHeaders.isBlank()) return;
        try {
            if (customHeaders.trim().startsWith("{")) {
                JsonNode root = objectMapper.readTree(customHeaders);
                if (root.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        String val = context.resolve(field.getValue().asText()).getResolvedContent();
                        reqBuilder.header(field.getKey(), val);
                    }
                }
            } else {
                for (String line : customHeaders.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String val = context.resolve(line.substring(colon + 1).trim()).getResolvedContent();
                        reqBuilder.header(key, val);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
