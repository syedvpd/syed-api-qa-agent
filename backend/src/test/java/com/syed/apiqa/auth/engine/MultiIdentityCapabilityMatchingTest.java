package com.syed.apiqa.auth.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.domain.ApiEndpoint;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MultiIdentityCapabilityMatchingTest {

    private SecurityDecisionEngine engine;
    private ObjectMapper mapper;
    private OpenAPI openAPI;
    private List<CredentialProfile> tenIdentities;

    @BeforeEach
    public void setup() {
        mapper = new ObjectMapper();
        engine = new SecurityDecisionEngine(mapper);

        // Build OpenAPI with OAuth2 & API Key security schemes
        openAPI = new OpenAPI();
        Components components = new Components();

        SecurityScheme oauthScheme = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .name("oauth2_auth");
        components.addSecuritySchemes("oauth2_auth", oauthScheme);

        SecurityScheme apiKeyScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .name("X-API-KEY")
                .in(SecurityScheme.In.HEADER);
        components.addSecuritySchemes("api_key_auth", apiKeyScheme);

        openAPI.setComponents(components);

        // Build 10 diverse generic identities with distinct capabilities
        tenIdentities = new ArrayList<>();

        // 1. Identity 01: Audit / Read-Only
        CredentialProfile p1 = new CredentialProfile("id-01", "AuditObserver", CredentialProfile.AuthStrategy.BEARER_TOKEN, "audit@test.internal", "pass");
        p1.setScopes(List.of("audit:read", "metrics:read"));
        tenIdentities.add(p1);

        // 2. Identity 02: User Account Manager
        CredentialProfile p2 = new CredentialProfile("id-02", "AccountManager", CredentialProfile.AuthStrategy.BEARER_TOKEN, "manager@test.internal", "pass");
        p2.setScopes(List.of("users:read", "users:write", "users:manage"));
        tenIdentities.add(p2);

        // 3. Identity 03: Inventory Clerk
        CredentialProfile p3 = new CredentialProfile("id-03", "InventoryClerk", CredentialProfile.AuthStrategy.BEARER_TOKEN, "clerk@test.internal", "pass");
        p3.setScopes(List.of("inventory:read", "inventory:update"));
        tenIdentities.add(p3);

        // 4. Identity 04: Financial Controller
        CredentialProfile p4 = new CredentialProfile("id-04", "FinanceController", CredentialProfile.AuthStrategy.BEARER_TOKEN, "finance@test.internal", "pass");
        p4.setScopes(List.of("finance:read", "finance:transact", "billing:invoice"));
        tenIdentities.add(p4);

        // 5. Identity 05: Diagnostics Operator
        CredentialProfile p5 = new CredentialProfile("id-05", "DiagnosticsOperator", CredentialProfile.AuthStrategy.BEARER_TOKEN, "diag@test.internal", "pass");
        p5.setScopes(List.of("health:check", "telemetry:export"));
        tenIdentities.add(p5);

        // 6. Identity 06: Catalog Editor
        CredentialProfile p6 = new CredentialProfile("id-06", "CatalogEditor", CredentialProfile.AuthStrategy.BEARER_TOKEN, "catalog@test.internal", "pass");
        p6.setScopes(List.of("catalog:read", "catalog:write"));
        tenIdentities.add(p6);

        // 7. Identity 07: Customer Support
        CredentialProfile p7 = new CredentialProfile("id-07", "SupportAgent", CredentialProfile.AuthStrategy.BEARER_TOKEN, "support@test.internal", "pass");
        p7.setScopes(List.of("tickets:read", "tickets:update", "users:read"));
        tenIdentities.add(p7);

        // 8. Identity 08: API Key Partner
        CredentialProfile p8 = new CredentialProfile("id-08", "ApiKeyPartner", CredentialProfile.AuthStrategy.API_KEY, null, null);
        p8.setHeaderName("X-API-KEY");
        p8.setToken("partner-secret-key-123");
        p8.setScopes(List.of("partner:ingest"));
        tenIdentities.add(p8);

        // 9. Identity 09: System Administrator
        CredentialProfile p9 = new CredentialProfile("id-09", "SystemAdmin", CredentialProfile.AuthStrategy.BEARER_TOKEN, "admin@test.internal", "pass");
        p9.setScopes(List.of("*", "sys:admin", "sys:config"));
        tenIdentities.add(p9);

        // 10. Identity 10: Security Auditor
        CredentialProfile p10 = new CredentialProfile("id-10", "SecAuditor", CredentialProfile.AuthStrategy.BEARER_TOKEN, "sec@test.internal", "pass");
        p10.setScopes(List.of("sec:audit", "sec:compliance"));
        tenIdentities.add(p10);
    }

    @Test
    @DisplayName("Verify deterministic capability matching selects FinanceController (id-04) for finance:transact")
    public void testFinanceCapabilityMatching() {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/finance/transactions");
        endpoint.setMethod("POST");
        endpoint.setSecurityRequirements("[{\"oauth2_auth\": [\"finance:transact\"]}]");

        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, openAPI, tenIdentities);

        assertEquals(OperationSecurityDecision.SecurityState.AUTH_REQUIRED, decision.getSecurityState());
        assertTrue(decision.isAuthenticationRequired());
        assertTrue(decision.isExecutionAllowed());
        assertNotNull(decision.getSelectedIdentity());
        assertEquals("id-04", decision.getSelectedIdentity().getId(), "Must select FinanceController, NOT profiles.get(0)");
        assertEquals("FinanceController", decision.getSelectedIdentity().getName());
    }

    @Test
    @DisplayName("Verify deterministic capability matching selects AccountManager (id-02) for users:manage")
    public void testAccountManagerMatching() {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/accounts/users");
        endpoint.setMethod("POST");
        endpoint.setSecurityRequirements("[{\"oauth2_auth\": [\"users:manage\"]}]");

        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, openAPI, tenIdentities);

        assertEquals(OperationSecurityDecision.SecurityState.AUTH_REQUIRED, decision.getSecurityState());
        assertNotNull(decision.getSelectedIdentity());
        assertEquals("id-02", decision.getSelectedIdentity().getId(), "Must select AccountManager");
    }

    @Test
    @DisplayName("Verify unmatchable scope results in NO_COMPATIBLE_IDENTITY and executionAllowed=false")
    public void testNoCompatibleIdentityBlocksExecution() {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/vault/nuclear-keys");
        endpoint.setMethod("DELETE");
        endpoint.setSecurityRequirements("[{\"oauth2_auth\": [\"quantum:launch_override\"]}]");

        // Identity 09 has "*", let's test with a list without wildcard admin
        List<CredentialProfile> standardProfiles = tenIdentities.subList(0, 8); // id-01 to id-08
        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, openAPI, standardProfiles);

        assertEquals(OperationSecurityDecision.SecurityState.AUTH_REQUIRED, decision.getSecurityState());
        assertNull(decision.getSelectedIdentity(), "No candidate identity has quantum:launch_override");
        assertFalse(decision.isExecutionAllowed(), "Execution MUST NOT be allowed when no compatible identity exists");
        assertTrue(decision.getReason().startsWith("NO_COMPATIBLE_IDENTITY"), "Reason must clearly state NO_COMPATIBLE_IDENTITY");
    }

    @Test
    @DisplayName("Verify API Key scheme matches ApiKeyPartner with matching header")
    public void testApiKeyMatching() {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/partner/data");
        endpoint.setMethod("POST");
        endpoint.setSecurityRequirements("[{\"api_key_auth\": []}]");

        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, openAPI, tenIdentities);

        assertEquals(OperationSecurityDecision.SecurityState.AUTH_REQUIRED, decision.getSecurityState());
        assertNotNull(decision.getSelectedIdentity());
        assertEquals("id-08", decision.getSelectedIdentity().getId(), "Must match ApiKeyPartner");
    }

    @Test
    @DisplayName("Verify contract-driven auth bootstrap is discovered without path heuristics")
    public void testContractDrivenAuthBootstrap() {
        ApiEndpoint endpoint = new ApiEndpoint();
        // Path has NO 'login' or 'token' in name!
        endpoint.setPath("/api/v3/auth/create-session");
        endpoint.setMethod("POST");
        endpoint.setSecurityRequirements("[]");
        endpoint.setRequestBodySchema("{\"type\":\"object\",\"properties\":{\"username\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"}}}");
        endpoint.setResponseSchemas("{\"type\":\"object\",\"properties\":{\"access_token\":{\"type\":\"string\"},\"token_type\":{\"type\":\"string\"}}}");

        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, openAPI, tenIdentities);

        assertEquals(OperationSecurityDecision.SecurityState.AUTH_BOOTSTRAP, decision.getSecurityState());
        assertFalse(decision.isAuthenticationRequired());
        assertTrue(decision.isExecutionAllowed());
        assertTrue(decision.getReason().contains("contract-declared authentication bootstrap"));
    }

    @Test
    @DisplayName("Verify public operation allows execution without credentials")
    public void testPublicOperation() {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/public/status");
        endpoint.setMethod("GET");
        endpoint.setSecurityRequirements("[]");

        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, openAPI, Collections.emptyList());

        assertEquals(OperationSecurityDecision.SecurityState.NO_SECURITY, decision.getSecurityState());
        assertFalse(decision.isAuthenticationRequired());
        assertTrue(decision.isExecutionAllowed());
    }
}
