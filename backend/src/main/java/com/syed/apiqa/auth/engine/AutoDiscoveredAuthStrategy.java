package com.syed.apiqa.auth.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.discovery.OpenApiSchemaRegistry;
import com.syed.apiqa.safety.PinnedConnectionManager;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Universal Auto-Discovered Authentication Strategy.
 * Inspects runtime credentials, contract metadata, and security schemes.
 * If authentication requires an API login endpoint, discovers it dynamically from the OpenAPI contract,
 * synthesizes a schema-compliant login request, dispatches HTTP, and captures the authentic session/token.
 * Never invents mock tokens; marks session AUTH_FAILED if authentication cannot be completed.
 */
@Component
public class AutoDiscoveredAuthStrategy implements AuthenticationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AutoDiscoveredAuthStrategy.class);

    private final BearerAuthStrategy bearerStrategy;
    private final ApiKeyAuthStrategy apiKeyStrategy;
    private final BasicAuthStrategy basicStrategy;
    private final CookieSessionStrategy cookieStrategy;
    private final CustomHeaderAuthStrategy customHeaderStrategy;
    private final OAuth2ClientCredentialsStrategy oauth2Strategy;
    private final OpenApiSchemaRegistry openApiSchemaRegistry;
    private final SsrfProtectionGuard ssrfGuard;
    private final ObjectMapper objectMapper;
    private final TokenExtractor tokenExtractor;

    public AutoDiscoveredAuthStrategy(BearerAuthStrategy bearerStrategy,
                                     ApiKeyAuthStrategy apiKeyStrategy,
                                     BasicAuthStrategy basicStrategy,
                                     CookieSessionStrategy cookieStrategy,
                                     CustomHeaderAuthStrategy customHeaderStrategy,
                                     OAuth2ClientCredentialsStrategy oauth2Strategy,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false) OpenApiSchemaRegistry openApiSchemaRegistry,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false) SsrfProtectionGuard ssrfGuard,
                                     ObjectMapper objectMapper,
                                     TokenExtractor tokenExtractor) {
        this.bearerStrategy = bearerStrategy;
        this.apiKeyStrategy = apiKeyStrategy;
        this.basicStrategy = basicStrategy;
        this.cookieStrategy = cookieStrategy;
        this.customHeaderStrategy = customHeaderStrategy;
        this.oauth2Strategy = oauth2Strategy;
        this.openApiSchemaRegistry = openApiSchemaRegistry;
        this.ssrfGuard = ssrfGuard;
        this.objectMapper = objectMapper;
        this.tokenExtractor = tokenExtractor;
    }

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.AUTO_DISCOVERED;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) throws Exception {
        if (profile == null) {
            session.setState(AuthLifecycleState.AUTH_FAILED);
            session.setLastErrorMessage("AUTH_CONFIGURATION_REQUIRED: No credential profile provided");
            return false;
        }

        // 1. Check for API Key (HeaderName provided)
        if (profile.getHeaderName() != null && !profile.getHeaderName().isBlank() &&
            profile.getToken() != null && !profile.getToken().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved API Key Authentication for identity '{}'", profile.getName());
            return apiKeyStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 2. Check for Cookie Session
        if (profile.getCookieName() != null && !profile.getCookieName().isBlank() &&
            profile.getToken() != null && !profile.getToken().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved Cookie Session Authentication for identity '{}'", profile.getName());
            return cookieStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 3. Check for Static Bearer Token
        if (profile.getToken() != null && !profile.getToken().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved Bearer Token Authentication for identity '{}'", profile.getName());
            return bearerStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 4. Check for Custom Header
        if (profile.getCustomHeaders() != null && !profile.getCustomHeaders().isEmpty()) {
            log.info("AUTO_DISCOVERED: Resolved Custom Header Authentication for identity '{}'", profile.getName());
            return customHeaderStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 5. Check for Username / Password Credentials
        if (profile.getUsernameOrEmail() != null && !profile.getUsernameOrEmail().isBlank() &&
            profile.getSecretOrPassword() != null && !profile.getSecretOrPassword().isBlank()) {

            OpenAPI openAPI = (openApiSchemaRegistry != null && session.getTestRunId() != null)
                    ? openApiSchemaRegistry.getOpenApi(session.getTestRunId()) : null;
            Map<String, Schema> schemas = (openApiSchemaRegistry != null && session.getTestRunId() != null)
                    ? openApiSchemaRegistry.getSchemas(session.getTestRunId()) : Collections.emptyMap();

            // Check if OpenAPI defines a Login or Token endpoint
            String loginPath = findLoginPath(openAPI);
            if (loginPath != null && ssrfGuard != null) {
                log.info("AUTO_DISCOVERED: Discovered login endpoint '{}' in contract for identity '{}'", loginPath, profile.getName());
                boolean loginSuccess = executeContractLogin(loginPath, profile, session, targetBaseUrl, openAPI, schemas);
                if (loginSuccess) {
                    return true;
                }
                log.warn("AUTO_DISCOVERED: Login failed against '{}' for identity '{}': {}", loginPath, profile.getName(), session.getLastErrorMessage());
                session.setState(AuthLifecycleState.AUTH_FAILED);
                return false;
            }

            // Check if contract explicitly specifies HTTP Basic Auth
            if (isBasicAuthDeclared(openAPI)) {
                log.info("AUTO_DISCOVERED: Resolved declared HTTP Basic Authentication for identity '{}'", profile.getName());
                return basicStrategy.authenticate(profile, session, targetBaseUrl);
            }

            // If Bearer/OAuth is required but no login endpoint discovered:
            log.warn("AUTO_DISCOVERED: No login endpoint discovered and Basic Auth not declared for identity '{}'", profile.getName());
            session.setState(AuthLifecycleState.AUTH_FAILED);
            session.setLastErrorMessage("AUTH_CONFIGURATION_REQUIRED: Could not discover login endpoint for token authentication");
            return false;
        }

        // 6. Refuse to guess: Halt and require configuration
        log.warn("AUTO_DISCOVERED: Cannot determine authentication scheme for identity '{}' without explicit credentials or schemes", profile.getName());
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("AUTH_CONFIGURATION_REQUIRED: No recognizable credentials provided for auto-discovery");
        return false;
    }

    private String findLoginPath(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getPaths() == null) return null;

        // Check OAuth2 tokenUrl in securitySchemes
        if (openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null) {
            for (SecurityScheme ss : openAPI.getComponents().getSecuritySchemes().values()) {
                if (ss.getFlows() != null) {
                    var flows = ss.getFlows();
                    if (flows.getPassword() != null && flows.getPassword().getTokenUrl() != null) {
                        return flows.getPassword().getTokenUrl();
                    }
                    if (flows.getClientCredentials() != null && flows.getClientCredentials().getTokenUrl() != null) {
                        return flows.getClientCredentials().getTokenUrl();
                    }
                }
            }
        }

        // Search paths for login/token endpoints
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem item = entry.getValue();
            if (item.getPost() != null) {
                String p = path.toLowerCase();
                if (p.endsWith("/login") || p.endsWith("/token") || p.contains("/auth/login") || p.contains("/auth/token") || p.contains("/oauth/token") || p.endsWith("/signin")) {
                    return path;
                }
            }
        }

        return null;
    }

    private boolean isBasicAuthDeclared(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getComponents() == null || openAPI.getComponents().getSecuritySchemes() == null) {
            return false;
        }
        for (SecurityScheme ss : openAPI.getComponents().getSecuritySchemes().values()) {
            if (ss.getType() == SecurityScheme.Type.HTTP && "basic".equalsIgnoreCase(ss.getScheme())) {
                return true;
            }
        }
        return false;
    }

    private boolean executeContractLogin(String loginPath, CredentialProfile profile, IdentitySession session,
                                         String targetBaseUrl, OpenAPI openAPI, Map<String, Schema> schemas) {
        try {
            String cleanBase = targetBaseUrl != null ? targetBaseUrl.replaceAll("/+$", "") : "";
            String cleanPath = loginPath.startsWith("/") ? loginPath : "/" + loginPath;
            String fullLoginUrl = cleanBase + cleanPath;

            SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(fullLoginUrl);

            PathItem pathItem = openAPI != null && openAPI.getPaths() != null ? openAPI.getPaths().get(loginPath) : null;
            Operation op = pathItem != null ? pathItem.getPost() : null;

            String contentType = "application/json";
            Schema<?> reqSchema = null;
            if (op != null && op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                var content = op.getRequestBody().getContent();
                if (content.containsKey("application/json")) {
                    contentType = "application/json";
                    reqSchema = content.get("application/json").getSchema();
                } else if (content.containsKey("application/x-www-form-urlencoded")) {
                    contentType = "application/x-www-form-urlencoded";
                    reqSchema = content.get("application/x-www-form-urlencoded").getSchema();
                } else if (!content.isEmpty()) {
                    var firstEntry = content.entrySet().iterator().next();
                    contentType = firstEntry.getKey();
                    reqSchema = firstEntry.getValue().getSchema();
                }
            }

            // Dereference schema
            if (reqSchema != null && reqSchema.get$ref() != null && schemas != null) {
                String refKey = reqSchema.get$ref().substring(reqSchema.get$ref().lastIndexOf('/') + 1);
                Schema<?> deref = schemas.get(refKey);
                if (deref != null) reqSchema = deref;
            }

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            if (reqSchema != null && reqSchema.getProperties() != null) {
                for (String propName : reqSchema.getProperties().keySet()) {
                    String lower = propName.toLowerCase();
                    if (lower.contains("email") || lower.contains("user") || lower.contains("client_id") || lower.contains("login") || lower.contains("account")) {
                        payloadMap.put(propName, profile.getUsernameOrEmail());
                    } else if (lower.contains("pass") || lower.contains("secret") || lower.contains("key")) {
                        payloadMap.put(propName, profile.getSecretOrPassword());
                    } else if (lower.contains("grant")) {
                        payloadMap.put(propName, "password");
                    }
                }
            }

            if (!payloadMap.containsKey("email") && !payloadMap.containsKey("username")) {
                if (profile.getUsernameOrEmail().contains("@")) {
                    payloadMap.put("email", profile.getUsernameOrEmail());
                } else {
                    payloadMap.put("username", profile.getUsernameOrEmail());
                }
            }
            if (!payloadMap.containsKey("password")) {
                payloadMap.put("password", profile.getSecretOrPassword());
            }

            byte[] bodyBytes;
            if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType)) {
                StringBuilder form = new StringBuilder();
                for (Map.Entry<String, Object> entry : payloadMap.entrySet()) {
                    if (form.length() > 0) form.append("&");
                    form.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
                }
                bodyBytes = form.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                bodyBytes = objectMapper.writeValueAsBytes(payloadMap);
            }

            HttpURLConnection conn = PinnedConnectionManager.openPinnedConnection(target, 15);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", contentType + "; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Syed-API-QA-Agent/1.0");
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.setDoOutput(true);

            try (var os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int statusCode = conn.getResponseCode();
            var in = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "";

            if (statusCode >= 200 && statusCode < 300) {
                TokenExtractor.ExtractedToken extracted = tokenExtractor.extract(responseBody, null);
                if (extracted != null && extracted.tokenValue() != null && !extracted.tokenValue().isBlank()) {
                    session.setAccessToken(extracted.tokenValue().trim());
                    session.setTokenType("Bearer");
                    session.setState(AuthLifecycleState.AUTHENTICATED);
                    session.setLastAuthenticatedAt(OffsetDateTime.now());
                    log.info("AUTO_DISCOVERED: Successfully authenticated identity '{}' against '{}'", profile.getName(), loginPath);
                    return true;
                }

                String setCookie = conn.getHeaderField("Set-Cookie");
                if (setCookie != null && !setCookie.isBlank()) {
                    String cookiePair = setCookie.split(";")[0];
                    if (cookiePair.contains("=")) {
                        String[] parts = cookiePair.split("=", 2);
                        session.addCookie(parts[0].trim(), parts[1].trim());
                    }
                    session.setState(AuthLifecycleState.AUTHENTICATED);
                    session.setLastAuthenticatedAt(OffsetDateTime.now());
                    log.info("AUTO_DISCOVERED: Captured cookie session for identity '{}' from '{}'", profile.getName(), loginPath);
                    return true;
                }

                session.setState(AuthLifecycleState.AUTH_FAILED);
                session.setLastErrorMessage("Login succeeded (HTTP " + statusCode + ") but token could not be extracted from response");
                return false;
            } else {
                session.setState(AuthLifecycleState.AUTH_FAILED);
                session.setLastErrorMessage("Login against " + loginPath + " failed with HTTP " + statusCode + ": " + responseBody);
                return false;
            }
        } catch (Exception e) {
            log.error("Exception during contract-driven login for identity '{}': {}", profile.getName(), e.getMessage());
            session.setState(AuthLifecycleState.AUTH_FAILED);
            session.setLastErrorMessage("Login exception: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session == null || connection == null) return;
        if (session.getAccessToken() != null && !session.getAccessToken().isBlank()) {
            connection.setRequestProperty("Authorization", session.getTokenType() + " " + session.getAccessToken().trim());
        }
        session.getAuthHeaders().forEach(connection::setRequestProperty);
        if (session.getCookieHeader() != null && !session.getCookieHeader().isBlank()) {
            connection.setRequestProperty("Cookie", session.getCookieHeader());
        } else if (!session.getCookies().isEmpty()) {
            StringBuilder cookieHeader = new StringBuilder();
            session.getCookies().forEach((k, v) -> {
                if (cookieHeader.length() > 0) cookieHeader.append("; ");
                cookieHeader.append(k).append("=").append(v);
            });
            connection.setRequestProperty("Cookie", cookieHeader.toString());
        }
    }
}
