package com.syed.apiqa.auth.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.domain.ApiEndpoint;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GenericAuthDecisionTest {

    private SecurityDecisionEngine engine;
    private ObjectMapper mapper;

    @BeforeEach
    public void setup() {
        mapper = new ObjectMapper();
        engine = new SecurityDecisionEngine(mapper);
    }

    @Test
    public void test1_SimplePublicGet() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/store/inventory");
        endpoint.setMethod("GET");
        // Explicit empty security array means public
        endpoint.setSecurityRequirements("[]");
        
        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, new OpenAPI(), Collections.emptyList());
        
        assertEquals(OperationSecurityDecision.SecurityState.NO_SECURITY, decision.getSecurityState());
        assertFalse(decision.isAuthenticationRequired());
        assertTrue(decision.isExecutionAllowed());
    }

    @Test
    public void test2_AuthenticatedOperation() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/pet");
        endpoint.setMethod("POST");
        endpoint.setSecurityRequirements("[{\"petstore_auth\": [\"write:pets\", \"read:pets\"]}]");

        CredentialProfile profile = new CredentialProfile();
        profile.setId("prof1");
        profile.setScopes(List.of("write:pets", "read:pets"));
        
        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, new OpenAPI(), List.of(profile));
        
        assertEquals(OperationSecurityDecision.SecurityState.AUTH_REQUIRED, decision.getSecurityState());
        assertTrue(decision.isAuthenticationRequired());
        assertEquals("prof1", decision.getSelectedIdentity().getId());
    }

    @Test
    public void test3_AuthenticationBootstrap() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint();
        endpoint.setPath("/user/login");
        endpoint.setMethod("GET");
        // Even if security is inherited or unknown, semantic path makes it bootstrap
        
        OperationSecurityDecision decision = engine.evaluateSecurity(endpoint, new OpenAPI(), Collections.emptyList());
        
        assertEquals(OperationSecurityDecision.SecurityState.AUTH_BOOTSTRAP, decision.getSecurityState());
        assertFalse(decision.isAuthenticationRequired());
    }
}
