package com.syed.apiqa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.contract.example.ExamplePriority;
import com.syed.apiqa.contract.example.ExamplePriorityEngine;
import com.syed.apiqa.contract.schema.*;
import com.syed.apiqa.contract.serializer.*;
import com.syed.apiqa.contract.validation.ResponseSchemaValidator;
import com.syed.apiqa.contract.validation.SchemaValidationFinding;
import com.syed.apiqa.discovery.ContractNormalizationService;
import com.syed.apiqa.discovery.OpenApi31Normalizer;
import com.syed.apiqa.domain.ContractConfidence;
import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import com.syed.apiqa.generation.DeterministicDataGenerator;
import com.syed.apiqa.safety.SensitiveDataClassifier;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Phase3ContractIntelligenceTest {

    private ObjectMapper objectMapper;
    private SensitiveDataClassifier sensitiveDataClassifier;
    private PatternGenerator patternGenerator;
    private DiscriminatorResolver discriminatorResolver;
    private SchemaGraphEngine schemaGraphEngine;
    private ExamplePriorityEngine examplePriorityEngine;
    private DeterministicDataGenerator dataGenerator;
    private ContractNormalizationService normalizationService;
    private ResponseSchemaValidator responseValidator;
    private ParameterSerializer parameterSerializer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sensitiveDataClassifier = new SensitiveDataClassifier();
        patternGenerator = new PatternGenerator();
        discriminatorResolver = new DiscriminatorResolver();
        schemaGraphEngine = new SchemaGraphEngine(discriminatorResolver, patternGenerator, sensitiveDataClassifier);
        examplePriorityEngine = new ExamplePriorityEngine(schemaGraphEngine);
        dataGenerator = new DeterministicDataGenerator(objectMapper, schemaGraphEngine, examplePriorityEngine);
        normalizationService = new ContractNormalizationService(new OpenApi31Normalizer());
        responseValidator = new ResponseSchemaValidator();
        parameterSerializer = new ParameterSerializer();
    }

    @Test
    @DisplayName("Regression Test: Endpoint requiring object never generates scalar 'root_...' string")
    void testObjectNeverGeneratesScalarString() {
        ObjectSchema userSchema = new ObjectSchema();
        userSchema.addProperty("email", new StringSchema().format("email"));
        userSchema.addProperty("name", new StringSchema());
        userSchema.setRequired(List.of("email", "name"));

        Random random = new Random(42L);
        Object generated = dataGenerator.generateValueForSchema(userSchema, "user", random, "run123");

        assertNotNull(generated);
        assertInstanceOf(Map.class, generated);
        assertFalse(generated.toString().startsWith("root_"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) generated;
        assertTrue(map.containsKey("email"));
        assertTrue(map.containsKey("name"));
        assertTrue(map.get("email").toString().contains("@"));
    }

    @Test
    @DisplayName("SchemaGraphEngine resolves recursive self-referential schemas without stack overflow")
    void testRecursiveSchemaCycleBreaker() {
        ObjectSchema userSchema = new ObjectSchema();
        userSchema.addProperty("id", new StringSchema().format("uuid"));
        userSchema.addProperty("name", new StringSchema());
        userSchema.addProperty("manager", new Schema<>().$ref("#/components/schemas/User"));

        Map<String, Schema> components = new HashMap<>();
        components.put("User", userSchema);

        Random random = new Random(100L);
        SchemaComplexityBudget budget = new SchemaComplexityBudget(10, 50, 5);

        SchemaGenerationResult result = schemaGraphEngine.generate(
                userSchema, "user", SchemaContext.REQUEST_BODY, budget, random, components
        );

        assertInstanceOf(SchemaGenerationResult.Success.class, result);
        SchemaGenerationResult.Success success = (SchemaGenerationResult.Success) result;
        assertInstanceOf(Map.class, success.value());

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) success.value();
        assertTrue(map.containsKey("id"));
        assertTrue(map.containsKey("name"));
        // Cycle should be expanded to depth 1 and broken cleanly at depth 2
        assertNotNull(map.get("manager"));
        @SuppressWarnings("unchecked")
        Map<String, Object> managerMap = (Map<String, Object>) map.get("manager");
        assertTrue(managerMap.containsKey("id"));
        assertNull(managerMap.get("manager"), "Cycle must be cleanly terminated at depth 2");
    }

    @Test
    @DisplayName("Contextual Projection: readOnly is excluded in requests; writeOnly is included")
    void testContextualProjection() {
        ObjectSchema schema = new ObjectSchema();
        StringSchema readOnlyField = new StringSchema();
        readOnlyField.setReadOnly(true);

        StringSchema writeOnlyField = new StringSchema();
        writeOnlyField.setWriteOnly(true);

        schema.addProperty("id", readOnlyField);
        schema.addProperty("password", writeOnlyField);
        schema.addProperty("username", new StringSchema());

        Random random = new Random(1L);
        SchemaGenerationResult reqResult = schemaGraphEngine.generate(
                schema, "acc", SchemaContext.REQUEST_BODY, new SchemaComplexityBudget(), random, Collections.emptyMap()
        );

        assertInstanceOf(SchemaGenerationResult.Success.class, reqResult);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) ((SchemaGenerationResult.Success) reqResult).value();

        assertFalse(map.containsKey("id"), "readOnly property must be omitted from request body");
        assertTrue(map.containsKey("password"), "writeOnly property must be included in request body");
        assertTrue(map.containsKey("username"));
    }

    @Test
    @DisplayName("Example Priority Engine respects priority order and assigns confidence")
    void testExamplePriorityEngineHierarchy() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("code", new StringSchema().example("SCHEMA_EX_001"));

        RequestBody rb = new RequestBody();
        Content content = new Content();
        MediaType mt = new MediaType();
        mt.setExample("MEDIA_TYPE_EX_999");
        content.addMediaType("application/json", mt);
        rb.setContent(content);

        Random random = new Random(10L);

        // 1. Media Type Example should beat Schema Example
        ExamplePriorityEngine.ResolvedPayload res1 = examplePriorityEngine.resolvePayload(
                null, null, rb, "application/json", schema, random, Collections.emptyMap()
        );
        assertEquals("MEDIA_TYPE_EX_999", res1.value());
        assertEquals(ExamplePriority.MEDIA_TYPE_EXAMPLE, res1.priorityUsed());
        assertEquals(ContractConfidence.HIGH, res1.confidence());

        // 2. User override should beat everything
        ExamplePriorityEngine.ResolvedPayload res2 = examplePriorityEngine.resolvePayload(
                "USER_OVERRIDE_VAL", "OP_EX", rb, "application/json", schema, random, Collections.emptyMap()
        );
        assertEquals("USER_OVERRIDE_VAL", res2.value());
        assertEquals(ExamplePriority.USER_OVERRIDE, res2.priorityUsed());
        assertEquals(ContractConfidence.HIGH, res2.confidence());
    }

    @Test
    @DisplayName("SensitiveDataClassifier detects secrets, generates valid dummies, and redacts")
    void testSensitiveDataClassifier() {
        assertTrue(sensitiveDataClassifier.isSensitive("user_password"));
        assertTrue(sensitiveDataClassifier.isSensitive("api_key"));
        assertTrue(sensitiveDataClassifier.isSensitive("access_token"));
        assertTrue(sensitiveDataClassifier.isSensitive("client_secret"));
        assertFalse(sensitiveDataClassifier.isSensitive("username"));
        assertFalse(sensitiveDataClassifier.isSensitive("category"));

        String dummy = sensitiveDataClassifier.generateSafeDummy("password", new Random(7L));
        assertTrue(dummy.startsWith("SafePassw0rd!"));

        assertEquals("[REDACTED_SECRET]", sensitiveDataClassifier.redact("super_secret_jwt_token"));
    }

    @Test
    @DisplayName("Payload Serializers correctly serialize JSON, Form-urlencoded, Multipart, and Text")
    void testPayloadSerializers() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "QA Test");
        data.put("amount", 100);

        // 1. JSON
        JsonPayloadSerializer jsonSer = new JsonPayloadSerializer(objectMapper);
        byte[] jsonBytes = jsonSer.serialize(data);
        String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
        assertTrue(jsonStr.contains("\"title\":\"QA Test\""));

        // 2. Form URL-Encoded
        FormUrlEncodedSerializer formSer = new FormUrlEncodedSerializer();
        byte[] formBytes = formSer.serialize(data);
        String formStr = new String(formBytes, StandardCharsets.UTF_8);
        assertEquals("title=QA+Test&amount=100", formStr);

        // 3. Multipart
        MultipartPayloadSerializer multiSer = new MultipartPayloadSerializer();
        byte[] multiBytes = multiSer.serialize(data);
        String multiStr = new String(multiBytes, StandardCharsets.UTF_8);
        assertTrue(multiStr.contains("Content-Disposition: form-data; name=\"title\""));
        assertTrue(multiStr.contains("QA Test"));

        // 4. Text plain
        TextPlainSerializer textSer = new TextPlainSerializer();
        byte[] textBytes = textSer.serialize("plain scalar payload");
        assertEquals("plain scalar payload", new String(textBytes, StandardCharsets.UTF_8));

        // 5. XML Truthful reporting
        XmlPayloadSerializer xmlSer = new XmlPayloadSerializer();
        assertEquals(com.syed.apiqa.domain.canonical.ContractCapability.SupportLevel.PARTIAL, xmlSer.getSupportLevel());
        byte[] xmlBytes = xmlSer.serialize(data);
        String xmlStr = new String(xmlBytes, StandardCharsets.UTF_8);
        assertTrue(xmlStr.contains("<title>QA Test</title>"));
    }

    @Test
    @DisplayName("ParameterSerializer formats path and query styles (matrix, label, deepObject)")
    void testParameterSerializerStyles() {
        // Path matrix style
        String matrix = parameterSerializer.serializePathParameter("id", "123", "matrix", false);
        assertEquals(";id=123", matrix);

        // Path label style
        String label = parameterSerializer.serializePathParameter("id", "456", "label", false);
        assertEquals(".456", label);

        // Query deepObject style
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("status", "active");
        filter.put("role", "admin");
        String deep = parameterSerializer.serializeQueryParameter("filter", filter, "deepObject", true);
        assertEquals("filter[status]=active&filter[role]=admin", deep);
    }

    @Test
    @DisplayName("ResponseSchemaValidator detects missing required fields and type mismatches")
    void testResponseSchemaValidator() throws Exception {
        ObjectSchema expected = new ObjectSchema();
        expected.addProperty("id", new IntegerSchema());
        expected.addProperty("email", new StringSchema());
        expected.setRequired(List.of("id", "email"));

        // 1. Valid payload
        String validJson = "{\"id\": 10, \"email\": \"user@test.com\"}";
        JsonNode validNode = objectMapper.readTree(validJson);
        List<SchemaValidationFinding> findings1 = responseValidator.validate(validNode, expected, Collections.emptyMap());
        assertTrue(findings1.isEmpty());

        // 2. Missing required field 'email' and wrong type for 'id'
        String invalidJson = "{\"id\": \"not_an_int\"}";
        JsonNode invalidNode = objectMapper.readTree(invalidJson);
        List<SchemaValidationFinding> findings2 = responseValidator.validate(invalidNode, expected, Collections.emptyMap());

        assertEquals(2, findings2.size());
        assertTrue(findings2.stream().anyMatch(f -> f.getViolationType().equals("MISSING_REQUIRED_PROPERTY")));
        assertTrue(findings2.stream().anyMatch(f -> f.getViolationType().equals("TYPE_MISMATCH")));
    }

    @Test
    @DisplayName("ResponseSchemaValidator generic composition test matrix: anyOf, oneOf, allOf, $ref, nullables, enums")
    void testGenericResponseSchemaValidatorMatrix() throws Exception {
        // A. anyOf with $ref and null
        ObjectSchema userProfile = new ObjectSchema();
        userProfile.addProperty("id", new StringSchema());
        userProfile.addProperty("name", new StringSchema());
        userProfile.setRequired(List.of("id", "name"));

        Map<String, Schema> components = Map.of("UserProfile", userProfile);

        ComposedSchema anyOfSchema = new ComposedSchema();
        Schema<?> refSchema = new Schema<>().$ref("#/components/schemas/UserProfile");
        Schema<?> nullSchema = new Schema<>().type("null");
        anyOfSchema.anyOf(List.of(refSchema, nullSchema));

        ObjectSchema envelope = new ObjectSchema();
        envelope.addProperty("success", new BooleanSchema());
        envelope.addProperty("data", anyOfSchema);

        // 1. Valid anyOf with Object
        JsonNode validObjNode = objectMapper.readTree("{\"success\": true, \"data\": {\"id\": \"u1\", \"name\": \"Syed\"}}");
        List<SchemaValidationFinding> f1 = responseValidator.validate(validObjNode, envelope, components);
        assertTrue(f1.isEmpty(), "Valid object inside anyOf must pass without findings");

        // 2. Valid anyOf with Null
        JsonNode validNullNode = objectMapper.readTree("{\"success\": true, \"data\": null}");
        List<SchemaValidationFinding> f2 = responseValidator.validate(validNullNode, envelope, components);
        assertTrue(f2.isEmpty(), "Null value inside nullable anyOf must pass");

        // 3. Invalid anyOf (scalar string when only object or null allowed)
        JsonNode invalidScalarNode = objectMapper.readTree("{\"success\": true, \"data\": \"invalid_string\"}");
        List<SchemaValidationFinding> f3 = responseValidator.validate(invalidScalarNode, envelope, components);
        assertFalse(f3.isEmpty(), "Scalar inside object/null anyOf must fail");
        assertTrue(f3.stream().anyMatch(f -> f.getViolationType().equals("COMPOSITION_ANYOF_VIOLATION")));

        // B. oneOf composition
        ComposedSchema oneOfSchema = new ComposedSchema();
        oneOfSchema.oneOf(List.of(new IntegerSchema(), new BooleanSchema()));

        JsonNode oneOfInt = objectMapper.readTree("42");
        assertTrue(responseValidator.validate(oneOfInt, oneOfSchema, Collections.emptyMap()).isEmpty(), "Exact 1 match in oneOf must pass");

        JsonNode oneOfStr = objectMapper.readTree("\"not_int_or_bool\"");
        assertFalse(responseValidator.validate(oneOfStr, oneOfSchema, Collections.emptyMap()).isEmpty(), "0 matches in oneOf must fail");

        // C. Enum and Constraints
        StringSchema enumSchema = new StringSchema();
        enumSchema.setEnum(List.of("ACTIVE", "INACTIVE", "SUSPENDED"));

        JsonNode validEnum = objectMapper.readTree("\"ACTIVE\"");
        assertTrue(responseValidator.validate(validEnum, enumSchema, Collections.emptyMap()).isEmpty());

        JsonNode invalidEnum = objectMapper.readTree("\"UNKNOWN_STATUS\"");
        List<SchemaValidationFinding> enumFindings = responseValidator.validate(invalidEnum, enumSchema, Collections.emptyMap());
        assertFalse(enumFindings.isEmpty());
        assertTrue(enumFindings.stream().anyMatch(f -> f.getViolationType().equals("INVALID_ENUM_VALUE")));
    }

    @Test
    @DisplayName("Scale Test: ContractNormalizationService processes 1,000 synthetic operations efficiently")
    void testLargeSpecNormalizationScale() {
        OpenAPI openAPI = new OpenAPI();
        openAPI.setInfo(new Info().title("Large Scale API").version("2.0.0"));

        for (int i = 0; i < 1000; i++) {
            PathItem item = new PathItem();
            Operation op = new Operation().operationId("op_" + i).summary("Operation " + i);
            ApiResponses responses = new ApiResponses();
            responses.addApiResponse("200", new ApiResponse().description("OK"));
            op.setResponses(responses);
            item.setGet(op);
            openAPI.path("/resource_" + i, item);
        }

        long start = System.currentTimeMillis();
        ContractNormalizationService.NormalizationResult result = normalizationService.normalize(openAPI, "https://api.scale.test/openapi.json");
        long duration = System.currentTimeMillis() - start;

        assertEquals(1000, result.model().getOperations().size());
        assertTrue(duration < 2000, "1,000 operations normalized in " + duration + "ms (must be < 2000ms)");
        assertTrue(result.qualityScore() > 0.0);
    }
}
