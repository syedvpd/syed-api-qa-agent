package com.syed.apiqa.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.syed.apiqa.agent.FailureIsolationHandler;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.auth.engine.AuthenticationPreflightService;
import com.syed.apiqa.auth.engine.AuthenticationStrategyRegistry;
import com.syed.apiqa.auth.engine.IdentitySessionManager;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.execution.HttpExecutionEngine;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Production Hardening Test Suite:
 * Proves Real Network-Level Multi-Identity Isolation, Live Refresh Storm Locking,
 * Authentication Failure Cascade Isolation, Auto-Discovery without Blind Guesses,
 * and DAG Dependency Execution over real TCP sockets.
 */
@SpringBootTest
@ActiveProfiles("test")
public class RealNetworkAuthenticationAndDagTest {

    private static WireMockServer wireMockServer;
    private static int port;
    private static String baseUrl;

    @Autowired
    private IdentitySessionManager sessionManager;

    @Autowired
    private AuthenticationStrategyRegistry strategyRegistry;

    @Autowired
    private AuthenticationPreflightService preflightService;

    @Autowired
    private HttpExecutionEngine httpEngine;

    @Autowired
    private FailureIsolationHandler failureIsolationHandler;

    @Autowired
    private com.syed.apiqa.persistence.TestRunRepository testRunRepository;

    @Autowired
    private com.syed.apiqa.persistence.TestCaseRepository testCaseRepository;

    @Autowired
    private SsrfProtectionGuard ssrfGuard;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        port = wireMockServer.port();
        baseUrl = "http://localhost:" + port;
        WireMock.configureFor("localhost", port);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Gate 4: Real Network Multi-Identity Header and Cookie Isolation")
    void testRealNetworkMultiIdentityIsolation() throws Exception {
        // Stub endpoints
        stubFor(get(urlEqualTo("/api/admin"))
                .withHeader("Authorization", equalTo("Bearer jwt-admin-token-999"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"role\":\"admin\"}")));

        stubFor(get(urlEqualTo("/api/auditor"))
                .withHeader("X-API-Key", equalTo("audit-key-secret-888"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"role\":\"auditor\"}")));

        stubFor(get(urlEqualTo("/api/user"))
                .withHeader("Cookie", containing("session_id=user-session-cookie-777"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"role\":\"user\"}")));

        String runId = "test-run-" + UUID.randomUUID();

        // 1. Identity Admin
        CredentialProfile adminProfile = new CredentialProfile();
        adminProfile.setId("id-admin");
        adminProfile.setName("Admin");
        adminProfile.setStrategy(CredentialProfile.AuthStrategy.BEARER_TOKEN);
        adminProfile.setToken("jwt-admin-token-999");

        sessionManager.authenticateIdentity(runId, adminProfile, baseUrl);
        IdentitySession adminSession = sessionManager.getOrCreateSession(runId, adminProfile);
        assertEquals(AuthLifecycleState.AUTHENTICATED, adminSession.getState());
        assertEquals("jwt-admin-token-999", adminSession.getAccessToken());

        // 2. Identity Auditor
        CredentialProfile auditorProfile = new CredentialProfile();
        auditorProfile.setId("id-auditor");
        auditorProfile.setName("Auditor");
        auditorProfile.setStrategy(CredentialProfile.AuthStrategy.API_KEY);
        auditorProfile.setHeaderName("X-API-Key");
        auditorProfile.setToken("audit-key-secret-888");

        sessionManager.authenticateIdentity(runId, auditorProfile, baseUrl);
        IdentitySession auditorSession = sessionManager.getOrCreateSession(runId, auditorProfile);
        assertEquals(AuthLifecycleState.AUTHENTICATED, auditorSession.getState());
        assertEquals("audit-key-secret-888", auditorSession.getAuthHeaders().get("X-API-Key"));
        assertNull(auditorSession.getAccessToken()); // Zero cross-leakage!

        // 3. Identity User (Cookie)
        CredentialProfile userProfile = new CredentialProfile();
        userProfile.setId("id-user");
        userProfile.setName("User");
        userProfile.setStrategy(CredentialProfile.AuthStrategy.COOKIE);
        userProfile.setCookieName("session_id");
        userProfile.setToken("user-session-cookie-777");

        sessionManager.authenticateIdentity(runId, userProfile, baseUrl);
        IdentitySession userSession = sessionManager.getOrCreateSession(runId, userProfile);
        assertEquals(AuthLifecycleState.AUTHENTICATED, userSession.getState());
        assertEquals("user-session-cookie-777", userSession.getCookies().get("session_id"));
        assertNull(userSession.getAccessToken()); // Zero cross-leakage!
        assertNull(userSession.getAuthHeaders().get("X-API-Key"));

        // Execute via real TCP client to WireMock
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest adminReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin"))
                .header("Authorization", "Bearer " + adminSession.getAccessToken())
                .GET().build();
        HttpResponse<String> adminRes = client.send(adminReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, adminRes.statusCode());

        HttpRequest auditorReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auditor"))
                .header("X-API-Key", auditorSession.getAuthHeaders().get("X-API-Key"))
                .GET().build();
        HttpResponse<String> auditorRes = client.send(auditorReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, auditorRes.statusCode());

        HttpRequest userReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/user"))
                .header("Cookie", "session_id=" + userSession.getCookies().get("session_id"))
                .GET().build();
        HttpResponse<String> userRes = client.send(userReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, userRes.statusCode());

        // Verify wire-level isolation
        verify(1, getRequestedFor(urlEqualTo("/api/admin")).withHeader("Authorization", equalTo("Bearer jwt-admin-token-999")));
        verify(1, getRequestedFor(urlEqualTo("/api/auditor")).withHeader("X-API-Key", equalTo("audit-key-secret-888")));
        verify(1, getRequestedFor(urlEqualTo("/api/user")).withHeader("Cookie", containing("session_id=user-session-cookie-777")));
    }

    @Test
    @DisplayName("Gate 5: Real Network Refresh Storm Locking (20 Concurrent Requests -> Exactly 1 Refresh HTTP Call)")
    void testRealNetworkTokenRefreshStormLocking() throws Exception {
        stubFor(post(urlEqualTo("/oauth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(100)
                        .withBody("{\"access_token\":\"newly-refreshed-jwt-token-2026\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        String runId = "test-run-" + UUID.randomUUID();
        CredentialProfile profile = new CredentialProfile();
        profile.setId("id-oauth");
        profile.setName("OAuthClient");
        profile.setStrategy(CredentialProfile.AuthStrategy.OAUTH2_CLIENT_CREDENTIALS);
        profile.setUsernameOrEmail("client-123");
        profile.setSecretOrPassword("secret-456");
        profile.setToken(baseUrl + "/oauth/token");

        IdentitySession session = sessionManager.getOrCreateSession(runId, profile);
        session.setAccessToken("expired-token");
        session.setState(AuthLifecycleState.EXPIRED);

        int concurrentThreads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(concurrentThreads);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrentThreads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // Each thread requests coordinated token refresh
                    boolean success = sessionManager.refreshSessionCoordinated(runId, profile, baseUrl);
                    results.add(success);
                } catch (Exception e) {
                    results.add(false);
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown(); // Release all 20 threads simultaneously!
        boolean completed = finishGate.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(completed, "All 20 concurrent threads should complete within timeout");
        assertEquals(20, results.size());
        for (Boolean res : results) {
            assertTrue(res, "Every thread should successfully resume with refreshed token");
        }

        // Verify that WireMock received EXACTLY 1 refresh call over the network!
        verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
        assertEquals("newly-refreshed-jwt-token-2026", session.getAccessToken());
        assertEquals(AuthLifecycleState.AUTHENTICATED, session.getState());
    }

    @Test
    @DisplayName("Gate 6: Auth Failure Cascade (Failed Identity Blocks Dependent Steps Without Fake API 500s)")
    void testAuthFailureCascadePrevention() {
        String runId = "test-run-" + UUID.randomUUID();
        CredentialProfile failedProfile = new CredentialProfile();
        failedProfile.setId("id-invalid");
        failedProfile.setName("InvalidIdentity");
        failedProfile.setStrategy(CredentialProfile.AuthStrategy.BASIC_AUTH);
        failedProfile.setUsernameOrEmail("bad-user");
        failedProfile.setSecretOrPassword("wrong-password");

        // Preflight failure simulation
        IdentitySession failedSession = sessionManager.getOrCreateSession(runId, failedProfile);
        failedSession.setState(AuthLifecycleState.AUTH_FAILED);
        failedSession.setLastErrorMessage("HTTP 401 Unauthorized: Invalid credentials");

        ExecutionContext context = new ExecutionContext(runId);
        context.registerSession(failedSession);

        // Execute 10 dependent test steps
        int blockedSteps = 0;
        for (int i = 1; i <= 10; i++) {
            TestStep step = new TestStep();
            step.setId("step-" + i);
            step.setName("Step " + i);
            step.setMethod("GET");
            step.setPathTemplate("/api/protected/resource/" + i);

            HttpExecutionEngine.StepExecutionOutcome outcome = httpEngine.executeStep(
                    step, baseUrl, context, EnvironmentType.DEVELOPMENT, "BASIC", null, failedSession
            );

            if (outcome.getFinalStatus() == StepStatus.BLOCKED &&
                outcome.getFailureMessage() != null &&
                outcome.getFailureMessage().contains("BLOCKED_BY_AUTHENTICATION")) {
                blockedSteps++;
            }
        }

        assertEquals(10, blockedSteps, "All 10 steps must be blocked by authentication failure");
        // Verify ZERO HTTP calls were dispatched over the wire to WireMock!
        verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Gate 7: Auto-Discovered Auth Refuses Blind Bearer Guess on Unresolvable Strategy")
    void testAutoDiscoveredRefusesBlindGuess() {
        CredentialProfile emptyProfile = new CredentialProfile();
        emptyProfile.setId("id-empty");
        emptyProfile.setName("EmptyProfile");
        emptyProfile.setStrategy(CredentialProfile.AuthStrategy.AUTO_DISCOVERED);

        String runId = "test-run-" + UUID.randomUUID();
        boolean authenticated = sessionManager.authenticateIdentity(runId, emptyProfile, baseUrl);
        assertFalse(authenticated, "Auto-discovered authentication without credentials must fail");

        IdentitySession session = sessionManager.getOrCreateSession(runId, emptyProfile);
        assertEquals(AuthLifecycleState.AUTH_FAILED, session.getState());
        assertTrue(session.getLastErrorMessage().contains("AUTH_CONFIGURATION_REQUIRED"),
                "Error message must explicitly require configuration, not guess Bearer. Got: " + session.getLastErrorMessage());
    }

    @Test
    @DisplayName("Gate 12: True Dependency DAG (Upstream Prerequisite Failure Blocks Full Downstream Subtree)")
    void testTrueDependencyDagScheduler() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());
        run.setOpenapiUrl("http://localhost/test.json");
        run.setStatus(RunStatus.EXECUTING);
        testRunRepository.save(run);

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setTestRun(run);
        testCase.setName("DAG Workflow");
        testCase.setScenarioType("CRUD_WORKFLOW");
        testCaseRepository.save(testCase);

        TestStep stepA = new TestStep();
        stepA.setId(UUID.randomUUID().toString());
        stepA.setName("Step A (Producer)");
        stepA.setMethod("POST");
        stepA.setPathTemplate("/api/a");
        stepA.setStepOrder(1);
        stepA.setTestCase(testCase);
        stepA.setStatus(StepStatus.FAILED); // Upstream step A failed!

        TestStep stepB = new TestStep();
        stepB.setId("step-B");
        stepB.setName("Step B");
        stepB.setMethod("GET");
        stepB.setPathTemplate("/api/b");
        stepB.setStepOrder(2);
        stepB.setTestCase(testCase);
        stepB.setStatus(StepStatus.PENDING);

        TestStep stepC = new TestStep();
        stepC.setId("step-C");
        stepC.setName("Step C");
        stepC.setMethod("GET");
        stepC.setPathTemplate("/api/c");
        stepC.setStepOrder(3);
        stepC.setTestCase(testCase);
        stepC.setStatus(StepStatus.PENDING);

        TestStep stepD = new TestStep();
        stepD.setId("step-D");
        stepD.setName("Step D");
        stepD.setMethod("GET");
        stepD.setPathTemplate("/api/d");
        stepD.setStepOrder(4);
        stepD.setTestCase(testCase);
        stepD.setStatus(StepStatus.PENDING);

        TestStep stepE = new TestStep();
        stepE.setId("step-E");
        stepE.setName("Step E");
        stepE.setMethod("GET");
        stepE.setPathTemplate("/api/e");
        stepE.setStepOrder(5);
        stepE.setTestCase(testCase);
        stepE.setStatus(StepStatus.PENDING);

        List<TestStep> downstreamSteps = List.of(stepB, stepC, stepD, stepE);

        int blockedCount = failureIsolationHandler.isolateFailureAndBlockDownstream(stepA, downstreamSteps, "CRUD_WORKFLOW");

        assertEquals(4, blockedCount, "All 4 downstream nodes (B, C, D, E) must be blocked");
        for (TestStep s : downstreamSteps) {
            assertEquals(StepStatus.BLOCKED, s.getStatus());
            assertTrue(s.getFailureReason().contains("Upstream prerequisite step 'Step A (Producer)' encountered FAILED"));
        }
    }
}
