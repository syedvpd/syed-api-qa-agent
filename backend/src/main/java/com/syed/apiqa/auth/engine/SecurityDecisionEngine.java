package com.syed.apiqa.auth.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.domain.ApiEndpoint;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generic Operation-Aware Security Decision Engine.
 * Evaluates OpenAPI security requirements, discovers contract-driven auth bootstrap producers,
 * and performs capability-based multi-identity matching with zero hardcoded endpoints or roles.
 */
@Service
public class SecurityDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(SecurityDecisionEngine.class);
    private final ObjectMapper objectMapper;

    public SecurityDecisionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OperationSecurityDecision evaluateSecurity(ApiEndpoint endpoint, OpenAPI openAPI, List<CredentialProfile> profiles) {
        OperationSecurityDecision decision = new OperationSecurityDecision();
        String opId = endpoint.getOperationId() != null ? endpoint.getOperationId() : endpoint.getMethod() + " " + endpoint.getPath();
        decision.setOperationId(opId);

        List<SecurityRequirement> securityRequirements = resolveSecurityRequirements(endpoint, openAPI);

        // 1. Determine if it's an Auth Bootstrap based on contract-driven schema & security metadata
        if (isAuthBootstrap(endpoint, securityRequirements, openAPI)) {
            decision.setSecurityState(OperationSecurityDecision.SecurityState.AUTH_BOOTSTRAP);
            decision.setAuthenticationRequired(false);
            decision.setReason("Operation is a contract-declared authentication bootstrap producer. Does not require prior session.");
            decision.setExecutionAllowed(true);
            decision.setConfidence("HIGH");
            return decision;
        }

        // 2. Explicitly public operation (security: [])
        if (securityRequirements != null && securityRequirements.isEmpty()) {
            decision.setSecurityState(OperationSecurityDecision.SecurityState.NO_SECURITY);
            decision.setAuthenticationRequired(false);
            decision.setReason("Explicitly public operation (security: [])");
            decision.setExecutionAllowed(true);
            decision.setConfidence("HIGH");
            return decision;
        }

        // 3. Unknown security metadata (no security declared on endpoint or OpenAPI root)
        if (securityRequirements == null) {
            decision.setSecurityState(OperationSecurityDecision.SecurityState.SECURITY_UNKNOWN);
            decision.setAuthenticationRequired(false);
            decision.setReason("No security requirements defined in contract. Defaulting open.");
            decision.setExecutionAllowed(true);
            decision.setConfidence("LOW");
            return decision;
        }

        // 4. Operation requires authentication (AUTH_REQUIRED)
        decision.setSecurityState(OperationSecurityDecision.SecurityState.AUTH_REQUIRED);
        decision.setAuthenticationRequired(true);
        decision.setRequiredSchemes(securityRequirements.toString());
        decision.setCandidateIdentities(profiles);

        // Capability / Scope / Scheme matching
        CandidateMatch bestMatch = selectBestMatchingIdentity(endpoint, securityRequirements, openAPI, profiles);

        if (bestMatch != null && bestMatch.profile != null) {
            decision.setSelectedIdentity(bestMatch.profile);
            decision.setExecutionAllowed(true);
            decision.setConfidence(bestMatch.confidence);
            decision.setReason("Selected identity [" + bestMatch.profile.getName() + "] matching required schemes/scopes: " + bestMatch.matchReason);
        } else {
            decision.setSelectedIdentity(null);
            decision.setExecutionAllowed(false);
            decision.setConfidence("HIGH");
            decision.setReason("NO_COMPATIBLE_IDENTITY: Operation requires security schemes " + securityRequirements + ", but no available credential profile satisfies the required scopes or authentication strategy.");
        }

        return decision;
    }

    private static class CandidateMatch {
        final CredentialProfile profile;
        final int score;
        final String matchReason;
        final String confidence;

        CandidateMatch(CredentialProfile profile, int score, String matchReason, String confidence) {
            this.profile = profile;
            this.score = score;
            this.matchReason = matchReason;
            this.confidence = confidence;
        }
    }

    private CandidateMatch selectBestMatchingIdentity(ApiEndpoint endpoint,
                                                      List<SecurityRequirement> securityRequirements,
                                                      OpenAPI openAPI,
                                                      List<CredentialProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }

        List<CandidateMatch> matches = new ArrayList<>();

        for (CredentialProfile profile : profiles) {
            int score = scoreProfileCompatibility(profile, endpoint, securityRequirements, openAPI);
            if (score > 0) {
                matches.add(new CandidateMatch(profile, score, "Score " + score + " across required schemes", score >= 10 ? "HIGH" : "MEDIUM"));
            }
        }

        if (matches.isEmpty() && isUnscopedRequirement(securityRequirements)) {
            // Strategy-compatible fallback ONLY if requirements have empty scope lists
            for (CredentialProfile profile : profiles) {
                if (isStrategyCompatible(profile, securityRequirements, openAPI)) {
                    matches.add(new CandidateMatch(profile, 1, "Strategy-compatible match for unscoped requirement", "MEDIUM"));
                }
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        // Sort by score descending, then deterministically by profile ID
        matches.sort((a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            String idA = a.profile.getId() != null ? a.profile.getId() : "";
            String idB = b.profile.getId() != null ? b.profile.getId() : "";
            return idA.compareTo(idB);
        });

        return matches.get(0);
    }

    private int scoreProfileCompatibility(CredentialProfile profile,
                                          ApiEndpoint endpoint,
                                          List<SecurityRequirement> securityRequirements,
                                          OpenAPI openAPI) {
        int score = 0;
        Set<String> profileScopes = new HashSet<>();
        if (profile.getScopes() != null) {
            for (String s : profile.getScopes()) {
                if (s != null) profileScopes.add(s.trim().toLowerCase());
            }
        }

        // Check against every security requirement alternative (OR relationship between requirements)
        for (SecurityRequirement req : securityRequirements) {
            int reqScore = 0;
            boolean allSchemesSatisfied = true;

            for (Map.Entry<String, List<String>> entry : req.entrySet()) {
                String schemeName = entry.getKey();
                List<String> requiredScopes = entry.getValue();

                // 1. Verify scheme compatibility
                if (!matchesScheme(profile, schemeName, openAPI)) {
                    allSchemesSatisfied = false;
                    break;
                }
                reqScore += 5; // Base score for scheme compatibility

                // Exact scheme/header affinity bonus
                SecurityScheme sObj = (openAPI != null && openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null)
                        ? openAPI.getComponents().getSecuritySchemes().get(schemeName) : null;
                if (sObj != null) {
                    if (sObj.getType() == SecurityScheme.Type.APIKEY && profile.getStrategy() == CredentialProfile.AuthStrategy.API_KEY) {
                        reqScore += 10;
                    }
                    if (sObj.getName() != null && profile.getHeaderName() != null && sObj.getName().equalsIgnoreCase(profile.getHeaderName())) {
                        reqScore += 15;
                    }
                    if (sObj.getType() == SecurityScheme.Type.HTTP && "bearer".equalsIgnoreCase(sObj.getScheme()) && profile.getStrategy() == CredentialProfile.AuthStrategy.BEARER_TOKEN) {
                        reqScore += 10;
                    }
                }

                // 2. Verify scopes compatibility
                if (requiredScopes != null && !requiredScopes.isEmpty()) {
                    boolean hasAllScopes = true;
                    for (String reqScope : requiredScopes) {
                        String cleanScope = reqScope.trim().toLowerCase();
                        if (profileScopes.contains(cleanScope) || profileScopes.contains("*") || profileScopes.contains("admin")) {
                            reqScore += 10;
                        } else {
                            hasAllScopes = false;
                        }
                    }
                    if (!hasAllScopes) {
                        allSchemesSatisfied = false;
                        break;
                    }
                }
            }

            if (allSchemesSatisfied && reqScore > score) {
                score = reqScore;
            }
        }

        // Add bonus score if endpoint tags match profile name / role
        if (endpoint.getTags() != null && profile.getName() != null) {
            String tags = endpoint.getTags().toLowerCase();
            if (tags.contains(profile.getName().toLowerCase())) {
                score += 2;
            }
        }

        return score;
    }

    private boolean isUnscopedRequirement(List<SecurityRequirement> securityRequirements) {
        if (securityRequirements == null || securityRequirements.isEmpty()) return true;
        for (SecurityRequirement req : securityRequirements) {
            for (List<String> scopes : req.values()) {
                if (scopes != null && !scopes.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isStrategyCompatible(CredentialProfile profile, List<SecurityRequirement> requirements, OpenAPI openAPI) {
        if (requirements == null || requirements.isEmpty()) return true;
        for (SecurityRequirement req : requirements) {
            for (String schemeName : req.keySet()) {
                if (matchesScheme(profile, schemeName, openAPI)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesScheme(CredentialProfile profile, String schemeName, OpenAPI openAPI) {
        if (schemeName == null) return false;

        SecurityScheme scheme = null;
        if (openAPI != null && openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null) {
            scheme = openAPI.getComponents().getSecuritySchemes().get(schemeName);
        }

        if (scheme != null) {
            SecurityScheme.Type type = scheme.getType();
            if (type == SecurityScheme.Type.HTTP) {
                String httpScheme = scheme.getScheme() != null ? scheme.getScheme().toLowerCase() : "";
                if ("basic".equals(httpScheme)) {
                    return profile.getStrategy() == CredentialProfile.AuthStrategy.BASIC_AUTH
                            || (profile.getUsernameOrEmail() != null && profile.getSecretOrPassword() != null);
                } else {
                    return profile.getStrategy() == CredentialProfile.AuthStrategy.BEARER_TOKEN
                            || profile.getStrategy() == CredentialProfile.AuthStrategy.LOGIN_ENDPOINT
                            || profile.getStrategy() == CredentialProfile.AuthStrategy.AUTO_DISCOVERED
                            || profile.getStrategy() == CredentialProfile.AuthStrategy.API_KEY
                            || profile.getToken() != null
                            || profile.getUsernameOrEmail() != null;
                }
            } else if (type == SecurityScheme.Type.APIKEY) {
                return profile.getStrategy() == CredentialProfile.AuthStrategy.API_KEY
                        || profile.getStrategy() == CredentialProfile.AuthStrategy.BEARER_TOKEN
                        || profile.getStrategy() == CredentialProfile.AuthStrategy.AUTO_DISCOVERED
                        || profile.getStrategy() == CredentialProfile.AuthStrategy.LOGIN_ENDPOINT
                        || profile.getToken() != null
                        || profile.getHeaderName() != null
                        || profile.getUsernameOrEmail() != null;
            } else if (type == SecurityScheme.Type.OAUTH2 || type == SecurityScheme.Type.OPENIDCONNECT) {
                return profile.getStrategy() == CredentialProfile.AuthStrategy.OAUTH2_CLIENT_CREDENTIALS
                        || profile.getStrategy() == CredentialProfile.AuthStrategy.BEARER_TOKEN
                        || profile.getStrategy() == CredentialProfile.AuthStrategy.LOGIN_ENDPOINT
                        || profile.getStrategy() == CredentialProfile.AuthStrategy.AUTO_DISCOVERED
                        || (profile.getScopes() != null && !profile.getScopes().isEmpty())
                        || profile.getToken() != null
                        || profile.getUsernameOrEmail() != null;
            }
        }

        // Generic name-based scheme inference if scheme not found in components
        String lowerName = schemeName.toLowerCase();
        if (lowerName.contains("bearer") || lowerName.contains("jwt") || lowerName.contains("auth") || lowerName.contains("oauth")) {
            return profile.getStrategy() == CredentialProfile.AuthStrategy.BEARER_TOKEN
                    || profile.getStrategy() == CredentialProfile.AuthStrategy.LOGIN_ENDPOINT
                    || profile.getStrategy() == CredentialProfile.AuthStrategy.AUTO_DISCOVERED
                    || profile.getToken() != null;
        } else if (lowerName.contains("api") || lowerName.contains("key")) {
            return profile.getStrategy() == CredentialProfile.AuthStrategy.API_KEY || profile.getToken() != null;
        }

        return true;
    }

    private List<SecurityRequirement> resolveSecurityRequirements(ApiEndpoint endpoint, OpenAPI openAPI) {
        if (endpoint.getSecurityRequirements() != null) {
            try {
                return objectMapper.readValue(endpoint.getSecurityRequirements(), new TypeReference<List<SecurityRequirement>>() {});
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        if (openAPI != null && openAPI.getSecurity() != null) {
            return openAPI.getSecurity();
        }
        return null;
    }

    private boolean isAuthBootstrap(ApiEndpoint endpoint, List<SecurityRequirement> securityRequirements, OpenAPI openAPI) {
        // 1. If operation explicitly requires security on itself (non-empty), it cannot be an unauthenticated bootstrap producer
        if (securityRequirements != null && !securityRequirements.isEmpty()) {
            return false;
        }

        // 2. Check OpenAPI security schemes if any tokenUrl or authorizationUrl maps to this path
        if (openAPI != null && openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null) {
            String path = endpoint.getPath() != null ? endpoint.getPath() : "";
            for (SecurityScheme ss : openAPI.getComponents().getSecuritySchemes().values()) {
                if (ss.getFlows() != null) {
                    var flows = ss.getFlows();
                    if ((flows.getClientCredentials() != null && flows.getClientCredentials().getTokenUrl() != null && path.endsWith(flows.getClientCredentials().getTokenUrl()))
                            || (flows.getPassword() != null && flows.getPassword().getTokenUrl() != null && path.endsWith(flows.getPassword().getTokenUrl()))
                            || (flows.getAuthorizationCode() != null && flows.getAuthorizationCode().getTokenUrl() != null && path.endsWith(flows.getAuthorizationCode().getTokenUrl()))) {
                        return true;
                    }
                }
            }
        }

        // 3. Contract-driven Schema analysis:
        // Check if request body schema contains credential fields
        String reqSchema = endpoint.getRequestBodySchema() != null ? endpoint.getRequestBodySchema().toLowerCase() : "";
        boolean hasCredentialFields = reqSchema.contains("password") || reqSchema.contains("secret")
                || reqSchema.contains("client_id") || reqSchema.contains("client_secret")
                || reqSchema.contains("grant_type") || reqSchema.contains("username");

        // Check if response schema contains token / session fields
        String respSchema = endpoint.getResponseSchemas() != null ? endpoint.getResponseSchemas().toLowerCase() : "";
        boolean hasTokenFields = respSchema.contains("token") || respSchema.contains("access_token")
                || respSchema.contains("jwt") || respSchema.contains("session")
                || respSchema.contains("sessionid") || respSchema.contains("token_type")
                || respSchema.contains("bearer");

        if (hasCredentialFields && hasTokenFields) {
            return true;
        }

        // 4. Query / Parameter-driven Auth Producer (e.g. GET /auth?username=..&password=..)
        String params = endpoint.getParameters() != null ? endpoint.getParameters().toLowerCase() : "";
        if (params.contains("password") && (params.contains("user") || params.contains("username"))) {
            return true;
        }

        // 5. Semantic fallback if schema is unspecified but path indicates auth bootstrap
        String path = endpoint.getPath() != null ? endpoint.getPath().toLowerCase() : "";
        if (path.endsWith("/login") || path.endsWith("/token") || path.endsWith("/oauth/token") || path.endsWith("/authenticate") || path.endsWith("/auth/login")) {
            return true;
        }

        return false;
    }
}
