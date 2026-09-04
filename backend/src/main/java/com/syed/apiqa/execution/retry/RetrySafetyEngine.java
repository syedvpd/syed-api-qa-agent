package com.syed.apiqa.execution.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Production-grade Retry Safety Engine.
 * Enforces strict HTTP semantics and prevents accidental duplicate mutations or retry storms.
 */
public class RetrySafetyEngine {

    private static final Logger log = LoggerFactory.getLogger(RetrySafetyEngine.class);

    public enum RetrySafety {
        SAFE_TO_RETRY,
        CONDITIONALLY_RETRYABLE,
        NOT_SAFE_TO_RETRY
    }

    public static class RetryDecision {
        private final boolean shouldRetry;
        private final long backoffDelayMs;
        private final String rationale;

        public RetryDecision(boolean shouldRetry, long backoffDelayMs, String rationale) {
            this.shouldRetry = shouldRetry;
            this.backoffDelayMs = backoffDelayMs;
            this.rationale = rationale;
        }

        public boolean shouldRetry() { return shouldRetry; }
        public long getBackoffDelayMs() { return backoffDelayMs; }
        public String getRationale() { return rationale; }
    }

    private static final Set<String> IDEMPOTENT_SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> IDEMPOTENT_MUTATION_METHODS = Set.of("PUT", "DELETE");

    /**
     * Classify an HTTP method and request context for retry safety.
     */
    public static RetrySafety classifyMethod(String method, Map<String, String> requestHeaders) {
        if (method == null) return RetrySafety.NOT_SAFE_TO_RETRY;
        String m = method.toUpperCase(Locale.ROOT).trim();

        if (IDEMPOTENT_SAFE_METHODS.contains(m)) {
            return RetrySafety.SAFE_TO_RETRY;
        }

        // Check for idempotency headers (e.g., Idempotency-Key, X-Idempotency-Key)
        if (requestHeaders != null) {
            for (String key : requestHeaders.keySet()) {
                if (key.equalsIgnoreCase("Idempotency-Key") || key.equalsIgnoreCase("X-Idempotency-Key")) {
                    return RetrySafety.CONDITIONALLY_RETRYABLE;
                }
            }
        }

        if (IDEMPOTENT_MUTATION_METHODS.contains(m)) {
            return RetrySafety.CONDITIONALLY_RETRYABLE;
        }

        return RetrySafety.NOT_SAFE_TO_RETRY;
    }

    /**
     * Determine if a failed execution should be retried based on method, status code, error, and attempt count.
     */
    public static RetryDecision evaluateRetry(String method,
                                              int statusCode,
                                              String errorType,
                                              int currentAttempt,
                                              int maxAttempts,
                                              Map<String, String> responseHeaders,
                                              Map<String, String> requestHeaders) {

        if (currentAttempt >= maxAttempts) {
            return new RetryDecision(false, 0, "Max retry attempts (" + maxAttempts + ") exhausted.");
        }

        RetrySafety safety = classifyMethod(method, requestHeaders);

        // 1. Rate Limiting (429) -> Always backoff and retry regardless of method
        if (statusCode == 429) {
            long backoffMs = 1000L;
            if (responseHeaders != null && responseHeaders.containsKey("Retry-After")) {
                try {
                    int sec = Integer.parseInt(responseHeaders.get("Retry-After").trim());
                    backoffMs = Math.min(5000L, sec * 1000L);
                } catch (NumberFormatException ignored) {}
            }
            return new RetryDecision(true, backoffMs, "Rate limited (429), retrying with backoff.");
        }

        // 2. Client Errors (400, 403, 404, 405, 422) -> NEVER RETRY
        if (statusCode == 400 || statusCode == 403 || statusCode == 404 || statusCode == 405 || statusCode == 422) {
            return new RetryDecision(false, 0, "Deterministic client/schema error (HTTP " + statusCode + "). Retry suppressed.");
        }

        // 3. Authentication Expiration (401) -> Can be retried AT MOST ONCE after re-authentication
        if (statusCode == 401 && currentAttempt == 1) {
            return new RetryDecision(true, 100L, "Authentication token expired (401). Targeted re-auth retry allowed.");
        }

        // 4. Mutation Safety: POST / PATCH without Idempotency-Key MUST NOT be retried on timeout/5xx
        if (safety == RetrySafety.NOT_SAFE_TO_RETRY) {
            return new RetryDecision(false, 0, "Unsafe non-idempotent mutation (" + method + "). Retry suppressed to avoid duplicate resource creation.");
        }

        // 5. Transient Gateway / Network / Timeout on Safe/Idempotent requests
        if (statusCode == 502 || statusCode == 503 || statusCode == 504 ||
                "TIMEOUT".equalsIgnoreCase(errorType) || "NETWORK_ERROR".equalsIgnoreCase(errorType) || "CONNECTION_FAILURE".equalsIgnoreCase(errorType)) {
            long delay = (long) Math.pow(2, currentAttempt) * 250L;
            return new RetryDecision(true, delay, "Transient failure (HTTP " + statusCode + " / " + errorType + ") on idempotent " + method + ".");
        }

        return new RetryDecision(false, 0, "Non-retryable condition (HTTP " + statusCode + ").");
    }
}
