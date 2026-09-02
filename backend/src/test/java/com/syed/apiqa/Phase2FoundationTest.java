package com.syed.apiqa;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import com.syed.apiqa.execution.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase2FoundationTest {

    @Test
    @DisplayName("ExecutionContext resolves Postman ${var}, {{var}}, and {param} syntax")
    void testExecutionContextMultiSyntaxResolution() {
        ExecutionContext ctx = new ExecutionContext("run-test-123");

        ctx.setVariable("customer_id", "cust_9999");
        ctx.setVariable("order.id", "ord_8888");
        ctx.setVariable("token", "jwt_secret_token");

        // 1. Postman ${var}
        ExecutionContext.ResolutionResult res1 = ctx.resolve("https://api.example.com/customers/${customer_id}");
        assertTrue(res1.isFullyResolved());
        assertEquals("https://api.example.com/customers/cust_9999", res1.getResolvedContent());

        // 2. Mustache {{var}}
        ExecutionContext.ResolutionResult res2 = ctx.resolve("Bearer {{token}}");
        assertTrue(res2.isFullyResolved());
        assertEquals("Bearer jwt_secret_token", res2.getResolvedContent());

        // 3. OpenAPI {param}
        ExecutionContext.ResolutionResult res3 = ctx.resolve("/orders/{order.id}");
        assertTrue(res3.isFullyResolved());
        assertEquals("/orders/ord_8888", res3.getResolvedContent());

        // 4. Missing variable
        ExecutionContext.ResolutionResult res4 = ctx.resolve("/items/${missing_item_id}");
        assertFalse(res4.isFullyResolved());
        assertEquals("missing_item_id", res4.getMissingVariable());
    }

    @Test
    @DisplayName("Variable provenance is recorded and queryable")
    void testVariableProvenanceTracking() {
        ExecutionContext ctx = new ExecutionContext("run-test-123");

        ExecutionContext.VariableProvenance prov = new ExecutionContext.VariableProvenance(
                "account.id", "acc_100", "POST /accounts", "$.data.account.id", "Admin"
        );
        ctx.setVariable("account.id", "acc_100", ExecutionContext.VariableScope.RESOURCE, prov);

        assertEquals("acc_100", ctx.getVariable("account.id"));
        assertNotNull(ctx.getProvenance("account.id"));
        assertEquals("POST /accounts", ctx.getProvenance("account.id").getSourceEndpoint());
        assertEquals("$.data.account.id", ctx.getProvenance("account.id").getSourceJsonPath());
        assertEquals("Admin", ctx.getProvenance("account.id").getIdentityName());
    }

    @Test
    @DisplayName("IdentitySession manages tokens, cookies, and session headers")
    void testIdentitySessionManagement() {
        IdentitySession session = new IdentitySession("id_admin", "Admin");
        session.setAccessToken("token_xyz");
        session.addCookie("session_id", "sess_12345");
        session.addCookie("csrf_token", "csrf_abcde");
        session.setAuthHeader("X-Tenant-Id", "tenant_prime");

        assertEquals("token_xyz", session.getAccessToken());
        assertEquals("csrf_token=csrf_abcde; session_id=sess_12345", session.getCookieHeader());
        assertEquals("tenant_prime", session.getAuthHeaders().get("X-Tenant-Id"));
    }

    @Test
    @DisplayName("CanonicalApiModel instantiates clean contract-driven entities")
    void testCanonicalApiModelIntegrity() {
        CanonicalApiModel model = new CanonicalApiModel();
        model.getMetadata().setTitle("Autonomous Test API");
        model.getMetadata().setVersion("1.0.0");
        model.getMetadata().setTargetBaseUrl("https://api.acme.com");

        CanonicalApiModel.CanonicalOperation op = new CanonicalApiModel.CanonicalOperation();
        op.setOperationId("createCustomer");
        op.setPath("/customers");
        op.setMethod("POST");
        op.setTags(List.of("Customers"));
        op.setRiskClassification("WRITE");

        model.getOperations().add(op);

        assertEquals(1, model.getOperations().size());
        assertEquals("createCustomer", model.getOperations().get(0).getOperationId());
        assertEquals("WRITE", model.getOperations().get(0).getRiskClassification());
    }
}
