package com.syed.apiqa.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.assertion.AssertionEngine;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.AssertionResultRepository;
import com.syed.apiqa.persistence.CapturedVariableRepository;
import com.syed.apiqa.persistence.ExecutionRepository;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.safety.SecretMasker;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Production-grade HTTP Execution Engine with Anti-DNS Rebinding Pinned Connection.
 * Connects TCP sockets directly to validated pinned IPs, preserving TLS SNI and Host header.
 * Supports all standard HTTP verbs including PATCH, GET, POST, PUT, DELETE,
 * variable substitution, SSRF validation, latency measurement, retry safety, rate limiting (429),
 * secret redaction, and variable capture.
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
    private final com.syed.apiqa.persistence.TestRunRepository testRunRepository;
    private final VariableExtractionEngine variableExtractionEngine;
    private final ObjectMapper objectMapper;

    @Value("${syed.safety.default-timeout-seconds:15}")
    private int defaultTimeoutSeconds;

    @Value("${syed.safety.max-response-size-bytes:2097152}")
    private int maxResponseSizeBytes;

    @Autowired
    public HttpExecutionEngine(SsrfProtectionGuard ssrfGuard,
                               SecretMasker secretMasker,
                               AssertionEngine assertionEngine,
                               ExecutionRepository executionRepository,
                               AssertionResultRepository assertionResultRepository,
                               CapturedVariableRepository capturedVariableRepository,
                               com.syed.apiqa.persistence.TestRunRepository testRunRepository,
                               VariableExtractionEngine variableExtractionEngine,
                               ObjectMapper objectMapper) {
        this.ssrfGuard = ssrfGuard;
        this.secretMasker = secretMasker;
        this.assertionEngine = assertionEngine;
        this.executionRepository = executionRepository;
        this.assertionResultRepository = assertionResultRepository;
        this.capturedVariableRepository = capturedVariableRepository;
        this.testRunRepository = testRunRepository;
        this.variableExtractionEngine = variableExtractionEngine != null ? variableExtractionEngine : new VariableExtractionEngine(objectMapper);
        this.objectMapper = objectMapper;
    }

    public HttpExecutionEngine(SsrfProtectionGuard ssrfGuard,
                               SecretMasker secretMasker,
                               AssertionEngine assertionEngine,
                               ExecutionRepository executionRepository,
                               AssertionResultRepository assertionResultRepository,
                               CapturedVariableRepository capturedVariableRepository,
                               com.syed.apiqa.persistence.TestRunRepository testRunRepository,
                               ObjectMapper objectMapper) {
        this(ssrfGuard, secretMasker, assertionEngine, executionRepository, assertionResultRepository, capturedVariableRepository, testRunRepository, null, objectMapper);
    }

    public HttpExecutionEngine(SsrfProtectionGuard ssrfGuard,
                               SecretMasker secretMasker,
                               AssertionEngine assertionEngine,
                               ExecutionRepository executionRepository,
                               AssertionResultRepository assertionResultRepository,
                               CapturedVariableRepository capturedVariableRepository,
                               ObjectMapper objectMapper) {
        this(ssrfGuard, secretMasker, assertionEngine, executionRepository, assertionResultRepository, capturedVariableRepository, null, null, objectMapper);
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
        return executeStep(step, baseUrl, context, envType, authType, authCredentials, null);
    }

    public StepExecutionOutcome executeStep(TestStep step,
                                            String baseUrl,
                                            ExecutionContext context,
                                            EnvironmentType envType,
                                            String authType,
                                            String authCredentials,
                                            IdentitySession identitySession) {

        // 1. Resolve Variables in Path & URL
        ExecutionContext.ResolutionResult urlResolution = context.resolve(step.getPathTemplate());
        if (!urlResolution.isFullyResolved()) {
            step.setStatus(StepStatus.REQUEST_NOT_EXECUTABLE);
            step.setFailureReason("REQUEST_NOT_EXECUTABLE: Missing required context variable or path parameter: {" + urlResolution.getMissingVariable() + "}");
            return new StepExecutionOutcome(StepStatus.REQUEST_NOT_EXECUTABLE, null, Collections.emptyList(), step.getFailureReason());
        }

        String relativePath = urlResolution.getResolvedContent();

        // 1.1 Pre-Request Local Contract Gate: Catch raw unresolved path parameters (e.g. {id}, {slug})
        if (relativePath != null && relativePath.matches(".*\\{[^}]+\\}.*")) {
            step.setStatus(StepStatus.REQUEST_NOT_EXECUTABLE);
            String reason = "REQUEST_NOT_EXECUTABLE: Path contains unresolved parameter in: " + relativePath + ". Upstream dependency was not satisfied.";
            step.setFailureReason(reason);
            return new StepExecutionOutcome(StepStatus.REQUEST_NOT_EXECUTABLE, null, Collections.emptyList(), reason);
        }

        if (step.getMethod() == null || step.getMethod().isBlank()) {
            step.setStatus(StepStatus.REQUEST_NOT_EXECUTABLE);
            String reason = "REQUEST_NOT_EXECUTABLE: Missing HTTP method specification.";
            step.setFailureReason(reason);
            return new StepExecutionOutcome(StepStatus.REQUEST_NOT_EXECUTABLE, null, Collections.emptyList(), reason);
        }

        String fullUrl = baseUrl + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        step.setResolvedUrl(fullUrl);

        // 2. Resolve Variables in Request Body (if present)
        String requestBody = step.getRequestBody();
        if (requestBody != null) {
            ExecutionContext.ResolutionResult bodyResolution = context.resolve(requestBody);
            requestBody = bodyResolution.getResolvedContent();
        }

        // 3. SSRF & Safety Pre-Check with Anti-DNS Rebinding IP Pinning
        SsrfProtectionGuard.ValidatedTarget validatedTarget;
        try {
            boolean allowLocal = (envType == EnvironmentType.DEVELOPMENT) || ssrfGuard.isAllowLocalTargets();
            validatedTarget = ssrfGuard.resolveAndValidate(fullUrl, allowLocal);
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
        return dispatchWithSafety(step, fullUrl, validatedTarget, requestBody, context, authType, authCredentials, identitySession);
    }

    private StepExecutionOutcome dispatchWithSafety(TestStep step,
                                                    String targetUrl,
                                                    SsrfProtectionGuard.ValidatedTarget validatedTarget,
                                                    String requestBody,
                                                    ExecutionContext context,
                                                    String authType,
                                                    String authCredentials,
                                                    IdentitySession identitySession) {

        String method = step.getMethod().toUpperCase();
        boolean isRetryable = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        int maxAttempts = isRetryable ? 2 : 1;
        int attempt = 0;

        Execution execution = new Execution();
        execution.setId(UUID.randomUUID().toString());
        execution.setTestStep(step);
        execution.setMethod(method);
        execution.setRequestUrl(secretMasker.maskUrl(targetUrl));
        execution.setRequestBody(secretMasker.maskBody(requestBody));

        OffsetDateTime startedAt = OffsetDateTime.now();
        execution.setStartedAt(startedAt);

        if ("PATCH".equalsIgnoreCase(method)) {
            while (attempt < maxAttempts) {
                attempt++;
                long startNanos = System.nanoTime();
                try {
                    return dispatchPinnedRaw(step, targetUrl, validatedTarget, method, requestBody, context, authType, authCredentials, identitySession, execution, startNanos);
                } catch (SocketTimeoutException e) {
                    long latencyMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
                    execution.setLatencyMs(latencyMs);
                    execution.setCompletedAt(OffsetDateTime.now());
                    execution.setStatus(StepStatus.TIMEOUT);
                    execution.setErrorType("TIMEOUT");
                    execution.setErrorDetails("Request timed out after " + attempt + " attempts.");
                    executionRepository.save(execution);
                    step.setStatus(StepStatus.TIMEOUT);
                    step.setFailureReason("Timeout after " + defaultTimeoutSeconds + "s");
                    return new StepExecutionOutcome(StepStatus.TIMEOUT, execution, Collections.emptyList(), step.getFailureReason());
                } catch (Exception e) {
                    log.error("HttpExecutionEngine exception dispatching PATCH to {}: {}", targetUrl, e.getMessage(), e);
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
        }

        while (attempt < maxAttempts) {
            attempt++;
            long startNanos = System.nanoTime();

            try {
                HttpURLConnection connection = com.syed.apiqa.safety.PinnedConnectionManager.openPinnedConnection(validatedTarget, defaultTimeoutSeconds);
                connection.setInstanceFollowRedirects(false);

                try {
                    connection.setRequestMethod(method);
                } catch (ProtocolException pe) {
                    throw pe;
                }

                connection.setRequestProperty("User-Agent", "Syed-API-QA-Agent/1.0");
                connection.setRequestProperty("Accept", "application/json, */*");

                if (requestBody != null && !requestBody.isBlank()) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                }

                // Inject Authentication
                applyAuth(connection, authType, authCredentials, identitySession);

                // Apply Custom Headers (e.g. If-None-Match, Idempotency-Key)
                if (step.getRequestHeaders() != null && !step.getRequestHeaders().isBlank()) {
                    applyCustomHeaders(connection, step.getRequestHeaders(), context);
                }

                // Mask and record request headers
                Map<String, List<String>> rawReqHeaders = connection.getRequestProperties();
                execution.setRequestHeaders(objectMapper.writeValueAsString(secretMasker.maskHeaders(rawReqHeaders)));

                // Write request body if present
                boolean hasBody = requestBody != null && !requestBody.isBlank() &&
                        !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
                if (hasBody) {
                    connection.setDoOutput(true);
                    try (OutputStream os = connection.getOutputStream()) {
                        os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                    }
                }

                // Send request and capture timing
                int statusCode = connection.getResponseCode();
                long elapsedNanos = System.nanoTime() - startNanos;
                long latencyMs = Math.max(1, elapsedNanos / 1_000_000);
                execution.setLatencyMs(latencyMs);
                execution.setResponseStatus(statusCode);

                // Record Response Headers (Sanitized)
                Map<String, List<String>> rawRespHeaders = connection.getHeaderFields();
                execution.setResponseHeaders(objectMapper.writeValueAsString(secretMasker.maskHeaders(rawRespHeaders)));

                // Handle 429 Too Many Requests (Rate Limiting)
                if (statusCode == 429 && attempt < maxAttempts) {
                    String retryAfterOpt = connection.getHeaderField("Retry-After");
                    int sleepSec = 1;
                    if (retryAfterOpt != null) {
                        try { sleepSec = Math.min(3, Integer.parseInt(retryAfterOpt.trim())); } catch (Exception ignored) {}
                    }
                    Thread.sleep(sleepSec * 1000L);
                    continue;
                }

                // Response body (bounded)
                InputStream in = (statusCode >= 200 && statusCode < 400) ? connection.getInputStream() : connection.getErrorStream();
                String rawBody = "";
                if (in != null) {
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        int total = 0;
                        boolean truncated = false;
                        while ((n = in.read(buf)) != -1) {
                            total += n;
                            if (total > maxResponseSizeBytes) {
                                baos.write(buf, 0, n);
                                truncated = true;
                                break;
                            }
                            baos.write(buf, 0, n);
                        }
                        rawBody = baos.toString(StandardCharsets.UTF_8);
                        if (truncated) {
                            rawBody += "\n[RESPONSE TRUNCATED - EXCEEDED 2MB LIMIT]";
                        }
                    }
                }
                execution.setResponseBody(secretMasker.maskBody(rawBody));
                execution.setCompletedAt(OffsetDateTime.now());

                // Evaluate Assertions
                List<AssertionResult> assertions = assertionEngine.evaluateAssertions(execution, step.getExpectedStatus(), "application/json");
                boolean allPassed = assertions.stream().allMatch(AssertionResult::isPassed);
                if (!allPassed) {
                    for (AssertionResult ar : assertions) {
                        if (!ar.isPassed()) {
                            log.error("ASSERTION FAILED for step {} {}: status={} body={} target={} msg={}",
                                    method, step.getPathTemplate(), statusCode, rawBody, ar.getTargetField(), ar.getMessage());
                        }
                    }
                }

                StepStatus finalStatus;
                if (statusCode == 429) {
                    finalStatus = StepStatus.RATE_LIMITED;
                } else if (statusCode == 401) {
                    finalStatus = StepStatus.AUTHENTICATION_ERROR;
                } else if (statusCode == 403) {
                    finalStatus = StepStatus.AUTHORIZATION_ERROR;
                } else if (allPassed) {
                    finalStatus = StepStatus.PASSED;
                } else {
                    finalStatus = StepStatus.FAILED;
                }
                execution.setStatus(finalStatus);

                // Extract ETag header for conditional request testing
                String etagHeader = connection.getHeaderField("ETag");
                if (etagHeader == null) etagHeader = connection.getHeaderField("etag");
                if (etagHeader != null) {
                    context.setVariable("etag", etagHeader);
                    String entity = extractEntityPrefix(step.getPathTemplate());
                    context.setVariable(entity + ".etag", etagHeader);
                }

                // 1. Persist execution evidence first to satisfy foreign key constraints
                Execution savedExecution = executionRepository.save(execution);
                Execution effectiveExecution = savedExecution != null ? savedExecution : execution;
                for (AssertionResult ar : assertions) {
                    assertionResultRepository.save(ar);
                }

                // 2. Extract and persist Variables from successful response payload referencing persisted execution
                if (finalStatus == StepStatus.PASSED && !rawBody.isBlank()) {
                    extractAndStoreVariables(rawBody, step, context, effectiveExecution);
                }

                step.setStatus(finalStatus);
                return new StepExecutionOutcome(finalStatus, effectiveExecution, assertions, null);

            } catch (SocketTimeoutException e) {
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

    private void applyAuth(HttpURLConnection connection, String authType, String credentials, IdentitySession identitySession) {
        if (identitySession != null) {
            if (identitySession.getAccessToken() != null && !identitySession.getAccessToken().isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + identitySession.getAccessToken().trim());
            }
            if (identitySession.getAuthHeaders() != null) {
                identitySession.getAuthHeaders().forEach(connection::setRequestProperty);
            }
            if (identitySession.getCookieHeader() != null && !identitySession.getCookieHeader().isBlank()) {
                connection.setRequestProperty("Cookie", identitySession.getCookieHeader());
            }
            return;
        }

        if (authType == null || credentials == null || credentials.isBlank() || "NONE".equalsIgnoreCase(authType)) {
            return;
        }

        switch (authType.toUpperCase()) {
            case "BEARER":
            case "BEARER_TOKEN":
                connection.setRequestProperty("Authorization", "Bearer " + credentials.trim());
                break;
            case "API_KEY":
                if (credentials.contains(":")) {
                    String[] parts = credentials.split(":", 2);
                    connection.setRequestProperty(parts[0].trim(), parts[1].trim());
                } else {
                    connection.setRequestProperty("X-Api-Key", credentials.trim());
                }
                break;
            case "BASIC":
            case "BASIC_AUTH":
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + encoded);
                break;
            case "COOKIE":
                connection.setRequestProperty("Cookie", credentials.trim());
                break;
            case "CUSTOM_HEADER":
                if (credentials.contains(":")) {
                    String[] parts = credentials.split(":", 2);
                    connection.setRequestProperty(parts[0].trim(), parts[1].trim());
                } else {
                    connection.setRequestProperty("X-Auth-Token", credentials.trim());
                }
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
            String endpoint = (step.getMethod() != null ? step.getMethod() : "GET") + " " + step.getPathTemplate();
            String stepName = step.getName() != null ? step.getName() : "Step";
            String identityName = context != null && context.getAllSessions() != null && !context.getAllSessions().isEmpty()
                    ? context.getAllSessions().keySet().iterator().next() : "default";

            List<VariableExtractionEngine.ExtractedVariable> extracted = variableExtractionEngine.extractAll(
                    root, entity, endpoint, stepName, identityName
            );

            TestRun run = null;
            if (context != null && context.getRunId() != null && testRunRepository != null) {
                run = testRunRepository.findById(context.getRunId()).orElse(null);
            }
            if (run == null && step.getTestCase() != null) {
                try {
                    run = step.getTestCase().getTestRun();
                } catch (Exception ignored) {}
            }

            Set<String> savedNames = new HashSet<>();
            for (VariableExtractionEngine.ExtractedVariable ev : extracted) {
                // Register runtime variable in execution context (preserves type & provenance)
                ExecutionContext.RuntimeVariable rv = new ExecutionContext.RuntimeVariable(
                        ev.getName(),
                        ev.getStringValue(),
                        ev.getType(),
                        ev.getRawValue(),
                        ev.isSensitive(),
                        ev.getProvenance()
                );
                context.setRuntimeVariable(rv);

                // Register created parent resource into ResourceRegistry for foreign key injection
                if (context.getResourceRegistry() != null && ev.getRawValue() != null) {
                    String nameLower = ev.getName().toLowerCase();
                    if ("id".equals(nameLower) || nameLower.endsWith(".id")) {
                        context.getResourceRegistry().registerCreatedResource(entity, ev.getRawValue(), Map.of("variable", ev.getName()));
                    } else if (nameLower.endsWith("_id")) {
                        String refEntity = nameLower.replaceAll("(_id)$", "").replaceAll("^.*\\.", "");
                        if (!refEntity.isBlank()) {
                            context.getResourceRegistry().registerCreatedResource(refEntity, ev.getRawValue(), Map.of("variable", ev.getName()));
                        }
                    }
                }

                // Do not persist sensitive variables in public unmasked captured_variables database table
                if (ev.isSensitive()) {
                    continue;
                }

                if (savedNames.add(ev.getName()) && ev.getStringValue() != null) {
                    CapturedVariable cv = new CapturedVariable();
                    cv.setId(UUID.randomUUID().toString());
                    cv.setTestRun(run);
                    cv.setExecution(execution);
                    cv.setVariableName(ev.getName());
                    cv.setVariableValue(ev.getStringValue());
                    capturedVariableRepository.save(cv);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract or persist variables for step {}: {}", step.getName(), e.getMessage());
        }
    }

    private String extractEntityPrefix(String path) {
        if (path == null || path.isBlank()) return "entity";
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (!p.isBlank() && !p.startsWith("{") && !p.equalsIgnoreCase("api") &&
                    !p.equalsIgnoreCase("v1") && !p.equalsIgnoreCase("v2") && !p.equalsIgnoreCase("v3")) {
                return p.toLowerCase();
            }
        }
        return "entity";
    }

    private void applyCustomHeaders(HttpURLConnection connection, String customHeaders, ExecutionContext context) {
        if (customHeaders == null || customHeaders.isBlank()) return;
        try {
            if (customHeaders.trim().startsWith("{")) {
                JsonNode root = objectMapper.readTree(customHeaders);
                if (root.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        String val = context.resolve(field.getValue().asText()).getResolvedContent();
                        connection.setRequestProperty(field.getKey(), val);
                    }
                }
            } else {
                for (String line : customHeaders.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String val = context.resolve(line.substring(colon + 1).trim()).getResolvedContent();
                        connection.setRequestProperty(key, val);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private StepExecutionOutcome dispatchPinnedRaw(TestStep step,
                                                   String targetUrl,
                                                   SsrfProtectionGuard.ValidatedTarget validatedTarget,
                                                   String method,
                                                   String requestBody,
                                                   ExecutionContext context,
                                                   String authType,
                                                   String authCredentials,
                                                   IdentitySession identitySession,
                                                   Execution execution,
                                                   long startNanos) throws Exception {

        String scheme = validatedTarget.originalUri().getScheme();
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        int port = validatedTarget.port() > 0 ? validatedTarget.port() : (isHttps ? 443 : 80);

        InetAddress connectAddress = (validatedTarget.pinnedAddress() != null)
                ? validatedTarget.pinnedAddress()
                : InetAddress.getByName(validatedTarget.originalHost());

        Socket rawSocket = new Socket();
        rawSocket.connect(new InetSocketAddress(connectAddress, port), defaultTimeoutSeconds * 1000);
        rawSocket.setSoTimeout(defaultTimeoutSeconds * 1000);

        Socket socket;
        if (isHttps) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                    rawSocket,
                    validatedTarget.originalHost(),
                    port,
                    true
            );
            SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(validatedTarget.originalHost())));
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();
            socket = sslSocket;
        } else {
            socket = rawSocket;
        }

        try (socket) {
            String path = validatedTarget.originalUri().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (validatedTarget.originalUri().getRawQuery() != null) {
                path += "?" + validatedTarget.originalUri().getRawQuery();
            }

            byte[] bodyBytes = (requestBody != null && !requestBody.isBlank())
                    ? requestBody.getBytes(StandardCharsets.UTF_8)
                    : new byte[0];

            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(validatedTarget.originalHostHeader()).append("\r\n");
            req.append("User-Agent: Syed-API-QA-Agent/1.0\r\n");
            req.append("Accept: application/json, */*\r\n");
            req.append("Connection: close\r\n");

            if (bodyBytes.length > 0) {
                req.append("Content-Type: application/json; charset=UTF-8\r\n");
                req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }

            // Auth
            if (identitySession != null) {
                if (identitySession.getAccessToken() != null && !identitySession.getAccessToken().isBlank()) {
                    req.append("Authorization: Bearer ").append(identitySession.getAccessToken().trim()).append("\r\n");
                }
                if (identitySession.getAuthHeaders() != null) {
                        identitySession.getAuthHeaders().forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
                    }
                    if (identitySession.getCookieHeader() != null && !identitySession.getCookieHeader().isBlank()) {
                        req.append("Cookie: ").append(identitySession.getCookieHeader()).append("\r\n");
                    }
                } else if (authType != null && authCredentials != null && !authCredentials.isBlank() && !"NONE".equalsIgnoreCase(authType)) {
                    switch (authType.toUpperCase()) {
                        case "BEARER":
                        case "BEARER_TOKEN":
                            req.append("Authorization: Bearer ").append(authCredentials.trim()).append("\r\n");
                            break;
                        case "API_KEY":
                            if (authCredentials.contains(":")) {
                            String[] p = authCredentials.split(":", 2);
                            req.append(p[0].trim()).append(": ").append(p[1].trim()).append("\r\n");
                        } else {
                            req.append("X-Api-Key: ").append(authCredentials.trim()).append("\r\n");
                        }
                        break;
                    case "BASIC":
                    case "BASIC_AUTH":
                        String encoded = Base64.getEncoder().encodeToString(authCredentials.getBytes(StandardCharsets.UTF_8));
                        req.append("Authorization: Basic ").append(encoded).append("\r\n");
                        break;
                    case "COOKIE":
                        req.append("Cookie: ").append(authCredentials.trim()).append("\r\n");
                        break;
                    case "CUSTOM_HEADER":
                        if (authCredentials.contains(":")) {
                            String[] p = authCredentials.split(":", 2);
                            req.append(p[0].trim()).append(": ").append(p[1].trim()).append("\r\n");
                        } else {
                            req.append("X-Auth-Token: ").append(authCredentials.trim()).append("\r\n");
                        }
                        break;
                }
            }

            // Custom headers
            if (step.getRequestHeaders() != null && !step.getRequestHeaders().isBlank()) {
                String headersStr = step.getRequestHeaders().trim();
                if (headersStr.startsWith("{")) {
                    JsonNode root = objectMapper.readTree(headersStr);
                    if (root.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> f = fields.next();
                            String val = context.resolve(f.getValue().asText()).getResolvedContent();
                            req.append(f.getKey()).append(": ").append(val).append("\r\n");
                        }
                    }
                } else {
                    for (String line : headersStr.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String key = line.substring(0, colon).trim();
                            String val = context.resolve(line.substring(colon + 1).trim()).getResolvedContent();
                            req.append(key).append(": ").append(val).append("\r\n");
                        }
                    }
                }
            }
            req.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                out.write(bodyBytes);
            }
            out.flush();

            // Read Response
            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            int statusCode = 200;
            if (statusLine != null) {
                String[] parts = statusLine.split(" ");
                if (parts.length >= 2) {
                    try { statusCode = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            long latencyMs = Math.max(1, elapsedNanos / 1_000_000);
            execution.setLatencyMs(latencyMs);
            execution.setResponseStatus(statusCode);

            // Read response headers
            Map<String, List<String>> rawRespHeaders = new LinkedHashMap<>();
            String headerLine;
            String etagHeader = null;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String hName = headerLine.substring(0, colon).trim();
                    String hVal = headerLine.substring(colon + 1).trim();
                    rawRespHeaders.computeIfAbsent(hName, k -> new ArrayList<>()).add(hVal);
                    if ("etag".equalsIgnoreCase(hName)) {
                        etagHeader = hVal;
                    }
                }
            }
            execution.setResponseHeaders(objectMapper.writeValueAsString(secretMasker.maskHeaders(rawRespHeaders)));

            // Read body
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            boolean truncated = false;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxResponseSizeBytes) {
                    baos.write(buf, 0, n);
                    truncated = true;
                    break;
                }
                baos.write(buf, 0, n);
            }
            String rawBody = baos.toString(StandardCharsets.UTF_8);
            if (truncated) {
                rawBody += "\n[RESPONSE TRUNCATED - EXCEEDED 2MB LIMIT]";
            }
            execution.setResponseBody(secretMasker.maskBody(rawBody));
            execution.setCompletedAt(OffsetDateTime.now());

            // Evaluate Assertions
            List<AssertionResult> assertions = assertionEngine.evaluateAssertions(execution, step.getExpectedStatus(), "application/json");
            boolean allPassed = assertions.stream().allMatch(AssertionResult::isPassed);
            if (!allPassed) {
                for (AssertionResult ar : assertions) {
                    if (!ar.isPassed()) {
                        log.error("ASSERTION FAILED for step {} {}: status={} body={} target={} msg={}",
                                method, step.getPathTemplate(), statusCode, rawBody, ar.getTargetField(), ar.getMessage());
                    }
                }
            }

            StepStatus finalStatus;
            if (statusCode == 429) {
                finalStatus = StepStatus.RATE_LIMITED;
            } else if (statusCode == 401) {
                finalStatus = StepStatus.AUTHENTICATION_ERROR;
            } else if (statusCode == 403) {
                finalStatus = StepStatus.AUTHORIZATION_ERROR;
            } else if (allPassed) {
                finalStatus = StepStatus.PASSED;
            } else {
                finalStatus = StepStatus.FAILED;
            }
            execution.setStatus(finalStatus);

            if (etagHeader != null) {
                context.setVariable("etag", etagHeader);
                String entity = extractEntityPrefix(step.getPathTemplate());
                context.setVariable(entity + ".etag", etagHeader);
            }

            if (finalStatus == StepStatus.PASSED && !rawBody.isBlank()) {
                extractAndStoreVariables(rawBody, step, context, execution);
            }

            executionRepository.save(execution);
            for (AssertionResult ar : assertions) {
                assertionResultRepository.save(ar);
            }

            step.setStatus(finalStatus);
            return new StepExecutionOutcome(finalStatus, execution, assertions, null);
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next == '\n' || next == -1) break;
                baos.write(c);
                baos.write(next);
            } else if (c == '\n') {
                break;
            } else {
                baos.write(c);
            }
        }
        if (c == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.UTF_8);
    }
}
