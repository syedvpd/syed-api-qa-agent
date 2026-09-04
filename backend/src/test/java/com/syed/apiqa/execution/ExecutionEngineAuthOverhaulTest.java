package com.syed.apiqa.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.assertion.AssertionEngine;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.contract.schema.*;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.AssertionResultRepository;
import com.syed.apiqa.persistence.CapturedVariableRepository;
import com.syed.apiqa.persistence.ExecutionRepository;
import com.syed.apiqa.safety.SecretMasker;
import com.syed.apiqa.safety.SensitiveDataClassifier;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ExecutionEngineAuthOverhaulTest {

    private ServerSocket serverSocket;
    private int listenPort;
    private Thread serverThread;
    private CountDownLatch serverReadyLatch;
    private final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedContentTypeHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedRequestBody = new AtomicReference<>();

    private HttpExecutionEngine engine;
    private SsrfProtectionGuard ssrfGuard;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        serverReadyLatch = new CountDownLatch(1);
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        listenPort = serverSocket.getLocalPort();

        serverThread = new Thread(() -> {
            try {
                serverReadyLatch.countDown();
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    int contentLength = 0;
                    String authHeader = null;
                    String contentTypeHeader = null;

                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        String lower = line.toLowerCase();
                        if (lower.startsWith("authorization:")) {
                            authHeader = line.substring(14).trim();
                        } else if (lower.startsWith("content-type:")) {
                            contentTypeHeader = line.substring(13).trim();
                        } else if (lower.startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }

                    StringBuilder bodyBuilder = new StringBuilder();
                    if (contentLength > 0) {
                        char[] buf = new char[contentLength];
                        int read = reader.read(buf, 0, contentLength);
                        if (read > 0) {
                            bodyBuilder.append(buf, 0, read);
                        }
                    }

                    receivedAuthHeader.set(authHeader);
                    receivedContentTypeHeader.set(contentTypeHeader);
                    receivedRequestBody.set(bodyBuilder.toString());

                    OutputStream out = socket.getOutputStream();
                    String responseBody;
                    String statusLine;

                    if (authHeader == null || authHeader.isEmpty()) {
                        statusLine = "HTTP/1.1 401 Unauthorized\r\n";
                        responseBody = "{\"error\":\"Unauthorized\", \"message\":\"Missing authentication header\"}";
                    } else {
                        statusLine = "HTTP/1.1 200 OK\r\n";
                        responseBody = "{\"id\":948,\"name\":\"Agent_948\",\"status\":\"active\",\"user_id\":948}";
                    }

                    String httpResponse = statusLine +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: " + responseBody.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" +
                            responseBody;
                    out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    socket.close();
                }
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
        assertTrue(serverReadyLatch.await(5, TimeUnit.SECONDS));

        ssrfGuard = Mockito.mock(SsrfProtectionGuard.class);
        SecretMasker secretMasker = new SecretMasker();
        AssertionEngine assertionEngine = new AssertionEngine(objectMapper);
        ExecutionRepository executionRepo = Mockito.mock(ExecutionRepository.class);
        AssertionResultRepository assertionRepo = Mockito.mock(AssertionResultRepository.class);
        CapturedVariableRepository variableRepo = Mockito.mock(CapturedVariableRepository.class);

        engine = new HttpExecutionEngine(
                ssrfGuard,
                secretMasker,
                assertionEngine,
                executionRepo,
                assertionRepo,
                variableRepo,
                objectMapper
        );
        ReflectionTestUtils.setField(engine, "defaultTimeoutSeconds", 5);
        ReflectionTestUtils.setField(engine, "maxResponseSizeBytes", 2097152);

        // Mock SSRF guard resolution for local server
        String baseUrl = "http://127.0.0.1:" + listenPort;
        URI uri = URI.create(baseUrl + "/api/v1/agents");
        InetAddress pinnedIp = InetAddress.getByName("127.0.0.1");
        SsrfProtectionGuard.ValidatedTarget target = new SsrfProtectionGuard.ValidatedTarget(
                uri, pinnedIp, "127.0.0.1", listenPort, uri.toString(), "127.0.0.1:" + listenPort, true
        );
        when(ssrfGuard.resolveAndValidate(anyString(), anyBoolean())).thenReturn(target);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    @DisplayName("Skyline 401 Bug Regression: Bearer token is guaranteed attached to outbound request")
    void skyline401BugRegression_bearerAuthAttachedAnd200OK() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());
        run.setEnvironmentType(EnvironmentType.STAGING);

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setTestRun(run);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(testCase);
        step.setName("POST Create Agent");
        step.setMethod("POST");
        step.setPathTemplate("/api/v1/agents");
        step.setRequestBody("{\"user\":948, \"name\":\"Test Agent\"}");
        step.setRequestHeaders("{\"Content-Type\":\"application/json\"}");
        step.setExpectedStatus(200);

        ExecutionContext context = new ExecutionContext(run.getId());
        String baseUrl = "http://127.0.0.1:" + listenPort;
        String token = "SKYLINE_SECRET_BEARER_TOKEN_999";

        IdentitySession identitySession = new IdentitySession("admin-id", "Primary Admin");
        identitySession.setState(AuthLifecycleState.AUTHENTICATED);
        identitySession.setAccessToken(token);
        identitySession.setAuthHeader("Authorization", "Bearer " + token);

        HttpExecutionEngine.StepExecutionOutcome outcome = engine.executeStep(
                step,
                baseUrl,
                context,
                EnvironmentType.STAGING,
                "BEARER",
                token,
                identitySession
        );

        assertEquals(StepStatus.PASSED, outcome.getFinalStatus(), "Step must pass when authenticated");
        assertNotNull(receivedAuthHeader.get(), "Outbound request MUST contain Authorization header");
        assertEquals("Bearer " + token, receivedAuthHeader.get(), "Outbound Authorization header must match Bearer token");
        
        // Check resource extraction into ResourceRegistry
        ResourceRegistry registry = context.getResourceRegistry();
        assertEquals(948, ((Number) registry.getLatestId("agents")).intValue(), "ResourceRegistry must capture created agent ID = 948");
        assertEquals(948, ((Number) registry.getLatestId("users")).intValue(), "ResourceRegistry must capture user_id = 948");
    }

    @Test
    @DisplayName("Unauthenticated Request: Server returns 401 when auth header is missing")
    void unauthenticatedProtectedRequest_flaggedOrFailed() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());
        run.setEnvironmentType(EnvironmentType.STAGING);

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setTestRun(run);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(testCase);
        step.setName("POST Create Agent Unauthenticated");
        step.setMethod("POST");
        step.setPathTemplate("/api/v1/agents");
        step.setRequestBody("{\"name\":\"Unauth Agent\"}");
        step.setExpectedStatus(200);

        ExecutionContext context = new ExecutionContext(run.getId());
        String baseUrl = "http://127.0.0.1:" + listenPort;

        HttpExecutionEngine.StepExecutionOutcome outcome = engine.executeStep(
                step,
                baseUrl,
                context,
                EnvironmentType.STAGING,
                "NONE",
                null,
                null
        );

        assertTrue(outcome.getFinalStatus() == StepStatus.AUTHENTICATION_ERROR || outcome.getFinalStatus() == StepStatus.FAILED);
        assertNull(receivedAuthHeader.get(), "Auth header should be null for unauthenticated request");
        assertEquals(401, outcome.getExecution().getResponseStatus());
    }

    @Test
    @DisplayName("Multi-Identity Role Isolation: Tokens from different roles never cross-contaminate")
    void multiIdentityRoleIsolation_tokensDoNotLeak() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());
        run.setEnvironmentType(EnvironmentType.STAGING);

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setTestRun(run);

        String baseUrl = "http://127.0.0.1:" + listenPort;
        ExecutionContext context = new ExecutionContext(run.getId());

        String adminToken = "ADMIN_TOKEN_123";
        String crmToken = "CRM_TOKEN_456";

        IdentitySession adminSession = new IdentitySession("admin-id", "Admin");
        adminSession.setState(AuthLifecycleState.AUTHENTICATED);
        adminSession.setAccessToken(adminToken);
        adminSession.setAuthHeader("Authorization", "Bearer " + adminToken);

        IdentitySession crmSession = new IdentitySession("crm-id", "CRM");
        crmSession.setState(AuthLifecycleState.AUTHENTICATED);
        crmSession.setAccessToken(crmToken);
        crmSession.setAuthHeader("Authorization", "Bearer " + crmToken);

        // Step 1 with Admin
        TestStep adminStep = new TestStep();
        adminStep.setId(UUID.randomUUID().toString());
        adminStep.setTestCase(testCase);
        adminStep.setName("Admin Step");
        adminStep.setMethod("POST");
        adminStep.setPathTemplate("/api/v1/agents");
        adminStep.setExpectedStatus(200);

        engine.executeStep(adminStep, baseUrl, context, EnvironmentType.STAGING, "BEARER", adminToken, adminSession);
        assertEquals("Bearer " + adminToken, receivedAuthHeader.get());

        // Step 2 with CRM
        TestStep crmStep = new TestStep();
        crmStep.setId(UUID.randomUUID().toString());
        crmStep.setTestCase(testCase);
        crmStep.setName("CRM Step");
        crmStep.setMethod("POST");
        crmStep.setPathTemplate("/api/v1/agents");
        crmStep.setExpectedStatus(200);

        engine.executeStep(crmStep, baseUrl, context, EnvironmentType.STAGING, "BEARER", crmToken, crmSession);
        assertEquals("Bearer " + crmToken, receivedAuthHeader.get());
        assertNotEquals("Bearer " + adminToken, receivedAuthHeader.get(), "Admin token must never leak into CRM request");
    }

    @Test
    @DisplayName("Schema Intelligence: SchemaGraphEngine generates realistic bounded integer data")
    void boundedDataGeneration_schemaGraphEngine() {
        SchemaGraphEngine schemaEngine = new SchemaGraphEngine(
                new DiscriminatorResolver(),
                new PatternGenerator(),
                new SensitiveDataClassifier()
        );

        IntegerSchema intSchema = new IntegerSchema();
        SchemaGenerationResult result = schemaEngine.generate(
                intSchema,
                "quantity",
                SchemaContext.REQUEST_BODY,
                new SchemaComplexityBudget(),
                new Random(),
                Collections.emptyMap()
        );

        assertTrue(result instanceof SchemaGenerationResult.Success);
        Object generatedValue = ((SchemaGenerationResult.Success) result).value();
        assertTrue(generatedValue instanceof Number, "Generated value must be a number");
        long val = ((Number) generatedValue).longValue();
        assertTrue(val >= 1 && val <= 50, "Unconstrained integer generation must be bounded between 1 and 50, got: " + val);

        StringSchema emailSchema = new StringSchema();
        emailSchema.setFormat("email");
        SchemaGenerationResult emailResult = schemaEngine.generate(
                emailSchema,
                "user_email",
                SchemaContext.REQUEST_BODY,
                new SchemaComplexityBudget(),
                new Random(),
                Collections.emptyMap()
        );
        assertTrue(emailResult instanceof SchemaGenerationResult.Success);
        assertTrue(((SchemaGenerationResult.Success) emailResult).value().toString().contains("@"));

        StringSchema enumSchema = new StringSchema();
        enumSchema.setEnum(List.of("ACTIVE", "INACTIVE", "PENDING"));
        SchemaGenerationResult enumResult = schemaEngine.generate(
                enumSchema,
                "status",
                SchemaContext.REQUEST_BODY,
                new SchemaComplexityBudget(),
                new Random(),
                Collections.emptyMap()
        );
        assertTrue(enumResult instanceof SchemaGenerationResult.Success);
        assertTrue(List.of("ACTIVE", "INACTIVE", "PENDING").contains(((SchemaGenerationResult.Success) enumResult).value()));
    }

    @Test
    @DisplayName("Resource Registry & Run Isolation: State remains isolated per execution context")
    void concurrentRunIsolation_stateDoesNotLeak() {
        ExecutionContext contextRunA = new ExecutionContext("RUN_A");
        ExecutionContext contextRunB = new ExecutionContext("RUN_B");

        contextRunA.getResourceRegistry().registerCreatedResource("users", 100, null);
        contextRunA.getResourceRegistry().registerCreatedResource("properties", 500, null);

        contextRunB.getResourceRegistry().registerCreatedResource("users", 200, null);
        contextRunB.getResourceRegistry().registerCreatedResource("properties", 600, null);

        assertEquals(100, contextRunA.getResourceRegistry().getLatestId("users"));
        assertEquals(200, contextRunB.getResourceRegistry().getLatestId("users"));

        assertNotEquals(
                contextRunA.getResourceRegistry().getLatestId("users"),
                contextRunB.getResourceRegistry().getLatestId("users"),
                "Run A and Run B resource registries must be strictly isolated"
        );
    }
}
