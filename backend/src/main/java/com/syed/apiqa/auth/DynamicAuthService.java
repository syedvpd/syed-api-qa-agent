package com.syed.apiqa.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.safety.SecretMasker;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Dynamic Authentication & Token Lifecycle Service.
 * Handles pre-test login, token extraction (JWT / Bearer), context injection,
 * secret masking, and mid-run token refresh upon receiving 401 Unauthorized.
 */
@Service
public class DynamicAuthService {

    private static final Logger log = LoggerFactory.getLogger(DynamicAuthService.class);

    private final SsrfProtectionGuard ssrfGuard;
    private final SecretMasker secretMasker;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public DynamicAuthService(SsrfProtectionGuard ssrfGuard, SecretMasker secretMasker) {
        this.ssrfGuard = ssrfGuard;
        this.secretMasker = secretMasker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static class AuthResult {
        private final boolean success;
        private final String token;
        private final String errorMessage;

        public AuthResult(boolean success, String token, String errorMessage) {
            this.success = success;
            this.token = token;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public String getToken() { return token; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * Executes login request and extracts the authentication token.
     */
    public AuthResult authenticate(String loginUrl, String payload, String tokenPath) {
        if (loginUrl == null || loginUrl.isBlank()) {
            return new AuthResult(false, null, "No login URL provided");
        }

        try {
            SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(loginUrl);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(target.pinnedUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("User-Agent", "Syed-API-QA-Agent/1.0")
                    .header("Accept", "application/json");

            if (target.isPinned()) {
                reqBuilder.header("Host", target.originalHostHeader());
            }

            HttpRequest.BodyPublisher body = (payload != null && !payload.isBlank())
                    ? HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody();

            HttpRequest request = reqBuilder.POST(body).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String token = extractToken(response.body(), tokenPath);
                if (token != null && !token.isBlank()) {
                    log.info("Dynamic authentication succeeded for {}. Extracted token of length {}", loginUrl, token.length());
                    return new AuthResult(true, token, null);
                } else {
                    return new AuthResult(false, null, "Token not found at path '" + tokenPath + "' in login response");
                }
            } else {
                return new AuthResult(false, null, "Login endpoint returned HTTP " + response.statusCode());
            }

        } catch (Exception e) {
            log.error("Authentication failure against {}: {}", loginUrl, e.getMessage());
            return new AuthResult(false, null, "Authentication network error: " + e.getMessage());
        }
    }

    /**
     * Extracts token using dot-notation JSON path (e.g. "token", "access_token", "data.jwt").
     */
    public String extractToken(String responseBody, String tokenPath) {
        if (responseBody == null || responseBody.isBlank()) return null;

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            if (tokenPath == null || tokenPath.isBlank() || "token".equalsIgnoreCase(tokenPath)) {
                // Heuristic lookup if path not explicitly given
                if (root.has("token")) return root.get("token").asText();
                if (root.has("access_token")) return root.get("access_token").asText();
                if (root.has("jwt")) return root.get("jwt").asText();
                if (root.has("id_token")) return root.get("id_token").asText();
            }

            // Dot-path navigation
            String[] segments = (tokenPath != null ? tokenPath : "token").split("\\.");
            JsonNode current = root;
            for (String segment : segments) {
                if (current != null && current.has(segment)) {
                    current = current.get(segment);
                } else {
                    return null;
                }
            }

            return current != null && current.isValueNode() ? current.asText() : null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempts to refresh an expired token.
     */
    public AuthResult refreshToken(String refreshUrl, String currentToken, String tokenPath) {
        if (refreshUrl == null || refreshUrl.isBlank()) {
            return new AuthResult(false, null, "No refresh URL configured");
        }

        try {
            SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(refreshUrl);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(target.pinnedUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Syed-API-QA-Agent/1.0")
                    .header("Accept", "application/json");

            if (target.isPinned()) {
                reqBuilder.header("Host", target.originalHostHeader());
            }

            if (currentToken != null && !currentToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + currentToken);
            }

            HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String newToken = extractToken(response.body(), tokenPath);
                if (newToken != null && !newToken.isBlank()) {
                    log.info("Dynamic token refresh succeeded. New token length: {}", newToken.length());
                    return new AuthResult(true, newToken, null);
                }
            }
            return new AuthResult(false, null, "Token refresh returned status " + response.statusCode());

        } catch (Exception e) {
            return new AuthResult(false, null, "Token refresh error: " + e.getMessage());
        }
    }
}
