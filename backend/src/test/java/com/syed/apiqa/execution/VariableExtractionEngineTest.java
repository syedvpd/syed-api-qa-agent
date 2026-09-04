package com.syed.apiqa.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class VariableExtractionEngineTest {

    private VariableExtractionEngine engine;
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        engine = new VariableExtractionEngine(objectMapper);
    }

    @Test
    @DisplayName("Phase 4C.1: Root scalar extraction")
    public void testRootScalarExtraction() throws Exception {
        JsonNode stringNode = objectMapper.readTree("\"hello-world-id\"");
        VariableExtractionEngine.ExtractionResult resString = engine.extractByPath(stringNode, "$");
        assertTrue(resString.isSuccess());
        assertEquals(VariableExtractionEngine.VariableType.STRING, resString.getVariable().getType());
        assertEquals("hello-world-id", resString.getVariable().getStringValue());

        JsonNode intNode = objectMapper.readTree("42");
        VariableExtractionEngine.ExtractionResult resInt = engine.extractByPath(intNode, "");
        assertTrue(resInt.isSuccess());
        assertEquals(VariableExtractionEngine.VariableType.INTEGER, resInt.getVariable().getType());
        assertEquals("42", resInt.getVariable().getStringValue());
    }

    @Test
    @DisplayName("Phase 4C.2: Root object extraction")
    public void testRootObjectExtraction() throws Exception {
        JsonNode obj = objectMapper.readTree("{\"id\":\"usr_123\",\"name\":\"Syed\",\"active\":true}");
        VariableExtractionEngine.ExtractionResult res = engine.extractByPath(obj, "id");
        assertTrue(res.isSuccess());
        assertEquals(VariableExtractionEngine.VariableType.STRING, res.getVariable().getType());
        assertEquals("usr_123", res.getVariable().getStringValue());

        VariableExtractionEngine.ExtractionResult resObj = engine.extractByPath(obj, "$");
        assertTrue(resObj.isSuccess());
        assertEquals(VariableExtractionEngine.VariableType.OBJECT, resObj.getVariable().getType());
        assertFalse(resObj.getVariable().getStringValue().contains("[object Object]"));
    }

    @Test
    @DisplayName("Phase 4C.3: Root array extraction")
    public void testRootArrayExtraction() throws Exception {
        JsonNode array = objectMapper.readTree("[{\"id\":\"item_1\"},{\"id\":\"item_2\"}]");
        VariableExtractionEngine.ExtractionResult res = engine.extractByPath(array, "[0].id");
        assertTrue(res.isSuccess());
        assertEquals(VariableExtractionEngine.VariableType.STRING, res.getVariable().getType());
        assertEquals("item_1", res.getVariable().getStringValue());

        VariableExtractionEngine.ExtractionResult res2 = engine.extractByPath(array, "[1].id");
        assertTrue(res2.isSuccess());
        assertEquals("item_2", res2.getVariable().getStringValue());
    }

    @Test
    @DisplayName("Phase 4C.4: Nested object and envelope extraction")
    public void testNestedObjectExtraction() throws Exception {
        String json = "{\"data\":{\"user\":{\"id\":\"u-99\",\"profile\":{\"email\":\"test@pawguard.io\",\"score\":98.5}}}}";
        JsonNode root = objectMapper.readTree(json);

        VariableExtractionEngine.ExtractionResult r1 = engine.extractByPath(root, "data.user.id");
        assertTrue(r1.isSuccess());
        assertEquals("u-99", r1.getVariable().getStringValue());

        VariableExtractionEngine.ExtractionResult r2 = engine.extractByPath(root, "data.user.profile.email");
        assertTrue(r2.isSuccess());
        assertEquals("test@pawguard.io", r2.getVariable().getStringValue());

        VariableExtractionEngine.ExtractionResult r3 = engine.extractByPath(root, "data.user.profile.score");
        assertTrue(r3.isSuccess());
        assertEquals(VariableExtractionEngine.VariableType.NUMBER, r3.getVariable().getType());
        assertEquals("98.5", r3.getVariable().getStringValue());
    }

    @Test
    @DisplayName("Phase 4C.5: Nested array and array element properties")
    public void testNestedArrayExtraction() throws Exception {
        String json = "{\"data\":{\"items\":[{\"id\":\"prod_1\",\"tags\":[\"tech\",\"hardware\"]},{\"id\":\"prod_2\"}]}}";
        JsonNode root = objectMapper.readTree(json);

        VariableExtractionEngine.ExtractionResult r1 = engine.extractByPath(root, "data.items[0].id");
        assertTrue(r1.isSuccess());
        assertEquals("prod_1", r1.getVariable().getStringValue());

        VariableExtractionEngine.ExtractionResult r2 = engine.extractByPath(root, "data.items[0].tags[1]");
        assertTrue(r2.isSuccess());
        assertEquals("hardware", r2.getVariable().getStringValue());
    }

    @Test
    @DisplayName("Phase 4D: Extraction syntax canonicalization (dot, jsonpath, jsonpointer)")
    public void testPathSyntaxCanonicalization() throws Exception {
        String json = "{\"data\":{\"items\":[{\"id\":\"target-id\"}]}}";
        JsonNode root = objectMapper.readTree(json);

        // Dot syntax
        VariableExtractionEngine.ExtractionResult rDot = engine.extractByPath(root, "data.items[0].id");
        assertTrue(rDot.isSuccess());
        assertEquals("target-id", rDot.getVariable().getStringValue());

        // JSONPath syntax
        VariableExtractionEngine.ExtractionResult rPath = engine.extractByPath(root, "$.data.items[0].id");
        assertTrue(rPath.isSuccess());
        assertEquals("target-id", rPath.getVariable().getStringValue());

        // JSON Pointer syntax
        VariableExtractionEngine.ExtractionResult rPointer = engine.extractByPath(root, "/data/items/0/id");
        assertTrue(rPointer.isSuccess());
        assertEquals("target-id", rPointer.getVariable().getStringValue());
    }

    @Test
    @DisplayName("Phase 4G: Type preservation (String, Integer, Number, Boolean, UUID, Object, Array, Null)")
    public void testTypePreservation() throws Exception {
        String json = "{" +
                "\"str\":\"value\"," +
                "\"num\":12345," +
                "\"dec\":3.14159," +
                "\"bool\":true," +
                "\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\"," +
                "\"obj\":{\"key\":\"val\"}," +
                "\"arr\":[1,2,3]," +
                "\"nullField\":null" +
                "}";
        JsonNode root = objectMapper.readTree(json);

        assertEquals(VariableExtractionEngine.VariableType.STRING, engine.extractByPath(root, "str").getVariable().getType());
        assertEquals(VariableExtractionEngine.VariableType.INTEGER, engine.extractByPath(root, "num").getVariable().getType());
        assertEquals(VariableExtractionEngine.VariableType.NUMBER, engine.extractByPath(root, "dec").getVariable().getType());
        assertEquals(VariableExtractionEngine.VariableType.BOOLEAN, engine.extractByPath(root, "bool").getVariable().getType());
        assertEquals(VariableExtractionEngine.VariableType.UUID, engine.extractByPath(root, "uuid").getVariable().getType());
        assertEquals(VariableExtractionEngine.VariableType.OBJECT, engine.extractByPath(root, "obj").getVariable().getType());
        assertEquals(VariableExtractionEngine.VariableType.ARRAY, engine.extractByPath(root, "arr").getVariable().getType());

        VariableExtractionEngine.ExtractionResult rNull = engine.extractByPath(root, "nullField");
        assertEquals(VariableExtractionEngine.ExtractionStatus.FOUND_NULL, rNull.getStatus());
        assertEquals(VariableExtractionEngine.VariableType.NULL, rNull.getVariable().getType());
        assertNull(rNull.getVariable().getStringValue());
    }

    @Test
    @DisplayName("Phase 4J & 4K: Explicit FOUND_NULL vs NOT_FOUND distinction")
    public void testNullVsNotFoundDistinction() throws Exception {
        String json = "{\"data\":{\"existingNull\":null,\"existingValue\":\"exists\"}}";
        JsonNode root = objectMapper.readTree(json);

        // Explicit null in payload
        VariableExtractionEngine.ExtractionResult rNull = engine.extractByPath(root, "data.existingNull");
        assertEquals(VariableExtractionEngine.ExtractionStatus.FOUND_NULL, rNull.getStatus());
        assertTrue(rNull.isSuccess());

        // Completely missing path
        VariableExtractionEngine.ExtractionResult rMissing = engine.extractByPath(root, "data.doesNotExist");
        assertEquals(VariableExtractionEngine.ExtractionStatus.NOT_FOUND, rMissing.getStatus());
        assertFalse(rMissing.isSuccess());
        assertNull(rMissing.getVariable());
        assertTrue(rMissing.getErrorMessage().contains("Path not found"));
    }

    @Test
    @DisplayName("Phase 4C Wildcard: Explicit UNSUPPORTED report for wildcard [*]")
    public void testWildcardUnsupportedReport() throws Exception {
        String json = "{\"items\":[{\"id\":\"1\"},{\"id\":\"2\"}]}";
        JsonNode root = objectMapper.readTree(json);

        VariableExtractionEngine.ExtractionResult rWildcard = engine.extractByPath(root, "items[*].id");
        assertEquals(VariableExtractionEngine.ExtractionStatus.UNSUPPORTED, rWildcard.getStatus());
        assertFalse(rWildcard.isSuccess());
        assertTrue(rWildcard.getErrorMessage().contains("unsupported"));
    }

    @Test
    @DisplayName("Phase 4H & 4I: Structured Object and Array extraction formatting")
    public void testStructuredObjectAndArrayFormatting() throws Exception {
        String json = "{\"data\":{\"user\":{\"id\":\"123\",\"role\":\"admin\"},\"roles\":[\"admin\",\"user\"]}}";
        JsonNode root = objectMapper.readTree(json);

        VariableExtractionEngine.ExtractionResult rObj = engine.extractByPath(root, "data.user");
        assertEquals(VariableExtractionEngine.VariableType.OBJECT, rObj.getVariable().getType());
        assertFalse(rObj.getVariable().getStringValue().contains("[object Object]"));
        assertTrue(rObj.getVariable().getStringValue().contains("\"role\":\"admin\""));

        VariableExtractionEngine.ExtractionResult rArr = engine.extractByPath(root, "data.roles");
        assertEquals(VariableExtractionEngine.VariableType.ARRAY, rArr.getVariable().getType());
        assertFalse(rArr.getVariable().getStringValue().contains("[object Object]"));
        assertTrue(rArr.getVariable().getStringValue().contains("\"admin\""));
    }

    @Test
    @DisplayName("Phase 4L: Stale variable protection in ExecutionContext")
    public void testStaleVariableProtection() {
        ExecutionContext context = new ExecutionContext("run-123");
        context.setVariable("order.id", "order-A");

        assertEquals("order-A", context.getVariable("order.id"));

        // Attempting to get nonexistent variable must NOT return stale value
        assertNull(context.getVariable("nonexistent.id"));
        assertNull(context.getVariable("user.id"));
    }

    @Test
    @DisplayName("Phase 4R & 4S: Secret protection and sensitivity classification")
    public void testSecretSensitivityClassification() {
        assertTrue(engine.isSensitive("access_token"));
        assertTrue(engine.isSensitive("token"));
        assertTrue(engine.isSensitive("password"));
        assertTrue(engine.isSensitive("apiKey"));
        assertTrue(engine.isSensitive("Authorization"));
        assertTrue(engine.isSensitive("refresh_token"));
        assertTrue(engine.isSensitive("client_secret"));

        assertFalse(engine.isSensitive("userId"));
        assertFalse(engine.isSensitive("orderId"));
        assertFalse(engine.isSensitive("email"));
        assertFalse(engine.isSensitive("createdAt"));
    }

    @Test
    @DisplayName("Phase 4Y: Variable template resolution (${var}, {{var}}, {var})")
    public void testTemplateResolution() {
        ExecutionContext context = new ExecutionContext("run-999");
        context.setVariable("userId", "usr_100");
        context.setVariable("entity.id", "ent_200");
        context.setVariable("page", "1");

        // 1. Postman syntax
        ExecutionContext.ResolutionResult r1 = context.resolve("/api/users/${userId}/posts");
        assertTrue(r1.isFullyResolved());
        assertEquals("/api/users/usr_100/posts", r1.getResolvedContent());

        // 2. Mustache syntax
        ExecutionContext.ResolutionResult r2 = context.resolve("/api/entities/{{entity.id}}?page={{page}}");
        assertTrue(r2.isFullyResolved());
        assertEquals("/api/entities/ent_200?page=1", r2.getResolvedContent());

        // 3. OpenAPI syntax
        ExecutionContext.ResolutionResult r3 = context.resolve("/api/v1/users/{userId}");
        assertTrue(r3.isFullyResolved());
        assertEquals("/api/v1/users/usr_100", r3.getResolvedContent());

        // 4. Missing variable
        ExecutionContext.ResolutionResult rMissing = context.resolve("/api/v1/users/{missingParam}");
        assertFalse(rMissing.isFullyResolved());
        assertEquals("missingParam", rMissing.getMissingVariable());
    }

    @Test
    @DisplayName("Phase 4AB: Concurrency & Run Isolation across ExecutionContexts")
    public void testConcurrencyAndRunIsolation() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCounter = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String runId = "run-" + index;
                    ExecutionContext ctx = new ExecutionContext(runId);
                    String varName = "user.id";
                    String varValue = "val-" + index;

                    ctx.setVariable(varName, varValue);
                    Thread.sleep(10);

                    // Ensure value was not mutated or leaked by other threads
                    if (varValue.equals(ctx.getVariable(varName))) {
                        successCounter.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threadCount, successCounter.get(), "All concurrent execution contexts must remain fully isolated");
    }

    @Test
    @DisplayName("Phase 4O: Recursive Auto-Discovery and Full Provenance Extraction")
    public void testAutoDiscoveryAndProvenance() throws Exception {
        String json = "{" +
                "\"data\": {" +
                "  \"id\": \"paw_pet_777\"," +
                "  \"name\": \"Fluffy\"," +
                "  \"owner\": {\"id\": \"usr_888\", \"email\": \"owner@pawguard.io\"}," +
                "  \"tags\": [\"dog\", \"friendly\"]" +
                "}" +
                "}";
        JsonNode root = objectMapper.readTree(json);

        List<VariableExtractionEngine.ExtractedVariable> vars = engine.extractAll(
                root, "pets", "POST /api/v1/pets", "Create Pet Step", "admin_user"
        );

        assertFalse(vars.isEmpty());

        // Check data.id extracted
        VariableExtractionEngine.ExtractedVariable petIdVar = vars.stream()
                .filter(v -> "data.id".equals(v.getName()) || "pets.id".equals(v.getName()) || "id".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(petIdVar);
        assertEquals("paw_pet_777", petIdVar.getStringValue());
        assertNotNull(petIdVar.getProvenance());
        assertEquals("POST /api/v1/pets", petIdVar.getProvenance().getSourceEndpoint());
        assertEquals("admin_user", petIdVar.getProvenance().getIdentityName());

        // Check owner email extracted
        VariableExtractionEngine.ExtractedVariable emailVar = vars.stream()
                .filter(v -> "data.owner.email".equals(v.getName()) || "pets.email".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(emailVar);
        assertEquals("owner@pawguard.io", emailVar.getStringValue());
    }
}
