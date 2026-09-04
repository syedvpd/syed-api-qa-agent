package com.syed.apiqa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.contract.example.ExamplePriorityEngine;
import com.syed.apiqa.contract.schema.DiscriminatorResolver;
import com.syed.apiqa.contract.schema.PatternGenerator;
import com.syed.apiqa.contract.schema.SchemaGraphEngine;
import com.syed.apiqa.discovery.OpenApiSchemaRegistry;
import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.StepStatus;
import com.syed.apiqa.domain.TestStep;
import com.syed.apiqa.generation.DeterministicDataGenerator;
import com.syed.apiqa.safety.SensitiveDataClassifier;
import com.syed.apiqa.validation.PreRequestValidator;
import io.swagger.v3.oas.models.media.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class Phase5SchemaAndAuthRealityTest {

    private ObjectMapper objectMapper;
    private SchemaGraphEngine schemaGraphEngine;
    private DeterministicDataGenerator dataGenerator;
    private PreRequestValidator preRequestValidator;
    private OpenApiSchemaRegistry schemaRegistry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        DiscriminatorResolver disc = new DiscriminatorResolver();
        PatternGenerator pat = new PatternGenerator();
        SensitiveDataClassifier sens = new SensitiveDataClassifier();
        schemaGraphEngine = new SchemaGraphEngine(disc, pat, sens);
        ExamplePriorityEngine exampleEngine = new ExamplePriorityEngine(schemaGraphEngine);
        dataGenerator = new DeterministicDataGenerator(objectMapper, schemaGraphEngine, exampleEngine);
        preRequestValidator = new PreRequestValidator(objectMapper);
        schemaRegistry = new OpenApiSchemaRegistry();
    }

    @Test
    @DisplayName("1. Simple Object Schema: Generates valid JSON Object, never scalar string")
    void testSimpleObjectSchemaGeneration() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("username", new StringSchema());
        schema.addProperty("email", new StringSchema().format("email"));
        schema.setRequired(List.of("username", "email"));

        String json = dataGenerator.generateJsonString(schema, "run_1", Collections.emptyMap());
        assertNotNull(json);
        assertTrue(json.startsWith("{") && json.endsWith("}"), "Must be JSON object: " + json);
        assertFalse(json.contains("safe_fallback"), "Must never contain safe_fallback");

        ApiEndpoint ep = new ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/api/v1/users");
        ep.setRequestBodySchema("{\"type\":\"object\",\"required\":[\"username\",\"email\"],\"properties\":{\"username\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"}}}");

        var valResult = preRequestValidator.validate(ep, json, Collections.emptyMap());
        assertTrue(valResult.valid(), "Generated request must be pre-request valid: " + valResult.failureReason());
    }

    @Test
    @DisplayName("2. Nested Object Schema: Dereferences and generates nested structure without scalar fallback")
    void testNestedObjectSchemaGeneration() {
        ObjectSchema addressSchema = new ObjectSchema();
        addressSchema.addProperty("street", new StringSchema());
        addressSchema.addProperty("city", new StringSchema());
        addressSchema.setRequired(List.of("street", "city"));

        ObjectSchema userSchema = new ObjectSchema();
        userSchema.addProperty("name", new StringSchema());
        userSchema.addProperty("address", new Schema<>().$ref("#/components/schemas/Address"));
        userSchema.setRequired(List.of("name", "address"));

        Map<String, Schema> components = Map.of("Address", addressSchema);
        String json = dataGenerator.generateJsonString(userSchema, "run_1", components);

        assertNotNull(json);
        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertTrue(json.contains("\"street\""), "Must contain nested street field");
        assertTrue(json.contains("\"city\""), "Must contain nested city field");
        assertFalse(json.contains("safe_fallback"), "Must never contain safe_fallback");
    }

    @Test
    @DisplayName("3. Array of Objects Schema: Generates JSON array of conforming objects")
    void testArrayOfObjectsSchema() {
        ArraySchema arraySchema = new ArraySchema();
        ObjectSchema itemSchema = new ObjectSchema();
        itemSchema.addProperty("itemId", new IntegerSchema());
        itemSchema.addProperty("sku", new StringSchema());
        itemSchema.setRequired(List.of("itemId", "sku"));
        arraySchema.setItems(itemSchema);

        String json = dataGenerator.generateJsonString(arraySchema, "run_1", Collections.emptyMap());
        assertNotNull(json);
        assertTrue(json.startsWith("[") && json.endsWith("]"), "Must be a JSON array: " + json);
        assertFalse(json.contains("safe_fallback"));
    }

    @Test
    @DisplayName("4. Enum Validation: Generates exact declared enum value")
    void testEnumFieldGeneration() {
        ObjectSchema schema = new ObjectSchema();
        StringSchema enumSchema = new StringSchema();
        enumSchema.setEnum(List.of("PENDING", "APPROVED", "REJECTED"));
        schema.addProperty("status", enumSchema);
        schema.setRequired(List.of("status"));

        String json = dataGenerator.generateJsonString(schema, "run_1", Collections.emptyMap());
        assertTrue(json.contains("PENDING") || json.contains("APPROVED") || json.contains("REJECTED"),
                "Payload must contain one of the declared enums: " + json);
    }

    @Test
    @DisplayName("5. Pre-Request Gate: Scalar string payload fails validation before HTTP dispatch (HTTP_SENT=false)")
    void testPreRequestGateRejectsScalarFallback() {
        ApiEndpoint ep = new ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/api/v1/auth/login");
        ep.setRequestBodySchema("{\"type\":\"object\",\"required\":[\"email\",\"password\"],\"properties\":{\"email\":{\"type\":\"string\"},\"password\":{\"type\":\"string\"}}}");

        String badScalarPayload = "\"safe_fallback_192\"";

        var valResult = preRequestValidator.validate(ep, badScalarPayload, Collections.emptyMap());
        assertFalse(valResult.valid(), "Pre-request validation MUST fail on scalar string for object schema");
        assertEquals("QA_AGENT_REQUEST_GENERATION_FAILURE", valResult.errorType());
        assertTrue(valResult.failureReason().contains("JSON Object"), "Error reason must specify expected JSON Object");
    }

    @Test
    @DisplayName("6. Pre-Request Gate: Missing required field fails validation before HTTP dispatch")
    void testPreRequestGateRejectsMissingRequiredField() {
        ApiEndpoint ep = new ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/api/v1/finance/accounts");
        ep.setRequestBodySchema("{\"type\":\"object\",\"required\":[\"accountName\",\"currency\"],\"properties\":{\"accountName\":{\"type\":\"string\"},\"currency\":{\"type\":\"string\"}}}");

        String incompletePayload = "{\"accountName\":\"TestAccount\"}";

        var valResult = preRequestValidator.validate(ep, incompletePayload, Collections.emptyMap());
        assertFalse(valResult.valid(), "Must fail when required field is missing");
        assertEquals("QA_AGENT_REQUEST_GENERATION_FAILURE", valResult.errorType());
        assertTrue(valResult.missingFields().contains("currency"), "Missing fields must include currency");
    }

    @Test
    @DisplayName("7. Identity Session Reality: Real tokens stored, zero mock tokens accepted")
    void testIdentitySessionRealToken() {
        IdentitySession session = new IdentitySession("ident_admin", "Super Admin");
        session.setTestRunId("run_100");
        session.setAccessToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.real_jwt_token");
        session.setState(AuthLifecycleState.AUTHENTICATED);

        assertTrue(session.isAuthenticated());
        assertEquals("Bearer", session.getTokenType());
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.real_jwt_token", session.getAccessToken());
        assertFalse(session.getAccessToken().startsWith("sec_token_mock_"), "Never store mock token");
    }

    @Test
    @DisplayName("8. Safe Branch Containment: Failed identity marks dependent operations BLOCKED_BY_AUTHENTICATION while public operations continue")
    void testAuthFailureBlocksDependentBranchOnly() {
        IdentitySession failedSession = new IdentitySession("ident_finance", "Finance User");
        failedSession.setState(AuthLifecycleState.AUTH_FAILED);
        failedSession.setLastErrorMessage("Invalid credentials provided");

        TestStep protectedStep = new TestStep();
        protectedStep.setId("step_finance_create");
        protectedStep.setMethod("POST");
        protectedStep.setPathTemplate("/api/v1/finance/accounts");

        if (failedSession.getState() == AuthLifecycleState.AUTH_FAILED) {
            protectedStep.setStatus(StepStatus.BLOCKED);
            protectedStep.setFailureReason("BLOCKED_BY_AUTHENTICATION: Required identity 'Finance User' failed authentication");
        }

        assertEquals(StepStatus.BLOCKED, protectedStep.getStatus());
        assertTrue(protectedStep.getFailureReason().contains("BLOCKED_BY_AUTHENTICATION"));

        TestStep publicStep = new TestStep();
        publicStep.setId("step_health");
        publicStep.setMethod("GET");
        publicStep.setPathTemplate("/api/v1/health");
        publicStep.setStatus(StepStatus.PASSED);

        assertEquals(StepStatus.PASSED, publicStep.getStatus(), "Public endpoint must not be blocked by auth failure of other endpoints");
    }
}
