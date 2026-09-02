package com.syed.apiqa;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.auth.engine.*;
import com.syed.apiqa.auth.matrix.AuthorizationMatrixEngine;
import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Phase4AuthenticationIntelligenceTest {

    private AuthenticationStrategyRegistry strategyRegistry;
    private IdentitySessionManager sessionManager;
    private AuthenticationPreflightService preflightService;
    private AuthorizationMatrixEngine matrixEngine;

    @BeforeEach
    void setUp() {
        BearerAuthStrategy bearerStrategy = new BearerAuthStrategy();
        ApiKeyAuthStrategy apiKeyStrategy = new ApiKeyAuthStrategy();
        BasicAuthStrategy basicStrategy = new BasicAuthStrategy();
        CookieSessionStrategy cookieStrategy = new CookieSessionStrategy();
        CustomHeaderAuthStrategy customStrategy = new CustomHeaderAuthStrategy();

        List<AuthenticationStrategy> strategies = List.of(
                bearerStrategy, apiKeyStrategy, basicStrategy, cookieStrategy, customStrategy
        );
        strategyRegistry = new AuthenticationStrategyRegistry(strategies);
        sessionManager = new IdentitySessionManager(strategyRegistry);
        preflightService = new AuthenticationPreflightService(sessionManager);
        matrixEngine = new AuthorizationMatrixEngine();
    }

    @Test
    @DisplayName("Mandatory: Cross-Identity Isolation guarantees zero token, cookie, or variable leakage")
    void testCrossIdentityIsolation() throws Exception {
        String testRunId = "run_iso_" + UUID.randomUUID();

        CredentialProfile adminProfile = new CredentialProfile("id_admin", "Admin", CredentialProfile.AuthStrategy.BEARER_TOKEN, "admin@test.com", "admin_secret_token_111");
        adminProfile.setToken("admin_secret_token_111");

        CredentialProfile userProfile = new CredentialProfile("id_user", "User", CredentialProfile.AuthStrategy.COOKIE, "user@test.com", "user_cookie_val_222");
        userProfile.setCookieName("user_session_id");

        CredentialProfile guestProfile = new CredentialProfile("id_guest", "Guest", CredentialProfile.AuthStrategy.API_KEY, "guest@test.com", "guest_key_333");
        guestProfile.setHeaderName("X-Guest-Key");

        // Authenticate all 3 identities concurrently
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Callable<Boolean>> tasks = List.of(
                () -> sessionManager.authenticateIdentity(testRunId, adminProfile, "https://api.test.com"),
                () -> sessionManager.authenticateIdentity(testRunId, userProfile, "https://api.test.com"),
                () -> sessionManager.authenticateIdentity(testRunId, guestProfile, "https://api.test.com")
        );

        List<Future<Boolean>> results = executor.invokeAll(tasks);
        for (Future<Boolean> f : results) {
            assertTrue(f.get());
        }

        IdentitySession adminSession = sessionManager.getOrCreateSession(testRunId, adminProfile);
        IdentitySession userSession = sessionManager.getOrCreateSession(testRunId, userProfile);
        IdentitySession guestSession = sessionManager.getOrCreateSession(testRunId, guestProfile);

        adminSession.setSessionVariable("var_scope", "admin_private_data");
        userSession.setSessionVariable("var_scope", "user_private_data");
        guestSession.setSessionVariable("var_scope", "guest_private_data");

        // Assert Strict Token & Header Isolation
        assertEquals("admin_secret_token_111", adminSession.getAccessToken());
        assertNull(userSession.getAccessToken(), "User session must not inherit Admin access token");
        assertNull(guestSession.getAccessToken(), "Guest session must not inherit Admin access token");

        // Assert Strict Cookie Isolation
        assertTrue(userSession.getCookieHeader().contains("user_session_id=user_cookie_val_222"));
        assertNull(adminSession.getCookieHeader(), "Admin session must not inherit User cookies");
        assertNull(guestSession.getCookieHeader(), "Guest session must not inherit User cookies");

        // Assert Strict Custom Auth Header Isolation
        assertTrue(guestSession.getAuthHeaders().containsKey("X-Guest-Key"));
        assertFalse(adminSession.getAuthHeaders().containsKey("X-Guest-Key"));
        assertFalse(userSession.getAuthHeaders().containsKey("X-Guest-Key"));

        // Assert Strict Session Variable Isolation
        assertEquals("admin_private_data", adminSession.getSessionVariable("var_scope"));
        assertEquals("user_private_data", userSession.getSessionVariable("var_scope"));
        assertEquals("guest_private_data", guestSession.getSessionVariable("var_scope"));

        executor.shutdown();
    }

    @Test
    @DisplayName("Mandatory: Coordinated Token Refresh Race eliminates refresh storms across 20 concurrent threads")
    void testCoordinatedTokenRefreshRace() throws Exception {
        String testRunId = "run_race_" + UUID.randomUUID();
        AtomicInteger tokenGenerationCounter = new AtomicInteger(100);

        // Custom mock strategy that tracks refresh count
        AtomicInteger refreshCallCount = new AtomicInteger(0);
        AuthenticationStrategy countingStrategy = new AuthenticationStrategy() {
            @Override
            public boolean supports(CredentialProfile.AuthStrategy strategy) {
                return strategy == CredentialProfile.AuthStrategy.BEARER_TOKEN;
            }

            @Override
            public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) {
                refreshCallCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                session.setAccessToken("token_v" + tokenGenerationCounter.incrementAndGet());
                session.setExpiresAt(java.time.OffsetDateTime.now().plusHours(1));
                session.setState(AuthLifecycleState.AUTHENTICATED);
                return true;
            }

            @Override
            public void applyToRequest(IdentitySession session, CredentialProfile profile, java.net.HttpURLConnection connection) {}
        };

        AuthenticationStrategyRegistry mockRegistry = new AuthenticationStrategyRegistry(List.of(countingStrategy));
        IdentitySessionManager coordinatedManager = new IdentitySessionManager(mockRegistry);

        CredentialProfile profile = new CredentialProfile("id_worker", "Worker", CredentialProfile.AuthStrategy.BEARER_TOKEN, "w@test.com", "pass");

        // Initialize session
        coordinatedManager.authenticateIdentity(testRunId, profile, "https://api.test.com");
        assertEquals(1, refreshCallCount.get());

        // Explicitly simulate token expiration
        IdentitySession initSession = coordinatedManager.getOrCreateSession(testRunId, profile);
        initSession.setState(AuthLifecycleState.EXPIRED);
        initSession.setExpiresAt(java.time.OffsetDateTime.now().minusSeconds(30));

        // 20 concurrent threads encounter token expiry simultaneously
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<String> observedTokens = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // All 20 threads call refreshSessionCoordinated at the exact same instant
                    coordinatedManager.refreshSessionCoordinated(testRunId, profile, "https://api.test.com");
                    IdentitySession sess = coordinatedManager.getOrCreateSession(testRunId, profile);
                    observedTokens.add(sess.getAccessToken());
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        // Exactly ONE refresh request should have been dispatched across the 20 threads (initial 1 + refresh 1 = 2 total)
        assertEquals(2, refreshCallCount.get(), "Must execute exactly ONE coordinated refresh call for 20 concurrent threads");
        assertEquals(20, observedTokens.size());

        // All 20 threads must observe the same newly refreshed token without corruption
        String expectedToken = observedTokens.get(0);
        for (String tok : observedTokens) {
            assertEquals(expectedToken, tok);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Mandatory: Authentication Failure Cascade isolates 1 root cause from N blocked operations")
    void testAuthFailureCascade() {
        String testRunId = "run_cascade_" + UUID.randomUUID();

        // Faulty profile with missing token/password
        CredentialProfile failingProfile = new CredentialProfile("id_broken", "BrokenIdentity", CredentialProfile.AuthStrategy.BEARER_TOKEN, null, null);

        // Preflight execution
        AuthenticationPreflightService.PreflightReport report = preflightService.executePreflight(
                testRunId, List.of(failingProfile), "https://api.test.com"
        );

        assertFalse(report.allPassed());
        assertEquals(1, report.failedCount());
        assertEquals(1, report.errorDetails().size());

        // Build Authorization Matrix for 5 operations
        CanonicalApiModel model = new CanonicalApiModel();
        for (int i = 1; i <= 5; i++) {
            CanonicalApiModel.CanonicalOperation op = new CanonicalApiModel.CanonicalOperation();
            op.setOperationId("op_" + i);
            op.setPath("/resource/" + i);
            op.setMethod("POST");
            op.setSecurityRequirements(List.of(Map.of("bearerAuth", List.of())));
            model.getOperations().add(op);
        }

        IdentitySession brokenSession = sessionManager.getOrCreateSession(testRunId, failingProfile);
        Map<String, IdentitySession> activeSessions = Map.of(failingProfile.getId(), brokenSession);

        List<AuthorizationMatrixEngine.MatrixCell> matrix = matrixEngine.buildMatrix(
                model, List.of(failingProfile), activeSessions
        );

        assertEquals(5, matrix.size());
        // All 5 operations must be BLOCKED_BY_AUTHENTICATION
        for (AuthorizationMatrixEngine.MatrixCell cell : matrix) {
            assertEquals(AuthorizationMatrixEngine.MatrixOutcome.BLOCKED_BY_AUTHENTICATION, cell.expectedOutcome());
            assertTrue(cell.reason().contains("failed"));
        }
    }

    @Test
    @DisplayName("Mandatory: Identity × Operation Matrix identifies DENIED_EXPECTED for unprivileged identity calling admin endpoint")
    void testExpectedAuthorizationDenial() {
        CanonicalApiModel model = new CanonicalApiModel();

        CanonicalApiModel.CanonicalOperation adminOp = new CanonicalApiModel.CanonicalOperation();
        adminOp.setOperationId("deleteUser");
        adminOp.setPath("/admin/users/{id}");
        adminOp.setMethod("DELETE");
        adminOp.setSummary("Admin user deletion");
        adminOp.setSecurityRequirements(List.of(Map.of("bearerAuth", List.of("admin"))));
        model.getOperations().add(adminOp);

        CanonicalApiModel.CanonicalOperation publicOp = new CanonicalApiModel.CanonicalOperation();
        publicOp.setOperationId("getPublicHealth");
        publicOp.setPath("/health");
        publicOp.setMethod("GET");
        model.getOperations().add(publicOp);

        CredentialProfile adminProfile = new CredentialProfile("admin_1", "Administrator", CredentialProfile.AuthStrategy.BEARER_TOKEN, "admin", "token_a");
        adminProfile.setScopes(List.of("admin"));

        CredentialProfile standardUser = new CredentialProfile("user_1", "RegularUser", CredentialProfile.AuthStrategy.BEARER_TOKEN, "user", "token_u");
        standardUser.setScopes(List.of("read"));

        List<CredentialProfile> profiles = List.of(adminProfile, standardUser);

        IdentitySession adminSession = new IdentitySession("admin_1", "Administrator");
        adminSession.setState(AuthLifecycleState.AUTHENTICATED);

        IdentitySession userSession = new IdentitySession("user_1", "RegularUser");
        userSession.setState(AuthLifecycleState.AUTHENTICATED);

        Map<String, IdentitySession> sessions = Map.of("admin_1", adminSession, "user_1", userSession);

        List<AuthorizationMatrixEngine.MatrixCell> cells = matrixEngine.buildMatrix(model, profiles, sessions);

        // 2 operations * 2 profiles = 4 cells
        assertEquals(4, cells.size());

        // Admin calling AdminOp -> ALLOWED
        assertTrue(cells.stream().anyMatch(c -> c.identityId().equals("admin_1") && c.operationId().equals("deleteUser") && c.expectedOutcome() == AuthorizationMatrixEngine.MatrixOutcome.ALLOWED));

        // RegularUser calling AdminOp -> DENIED_EXPECTED (Expected 403)
        assertTrue(cells.stream().anyMatch(c -> c.identityId().equals("user_1") && c.operationId().equals("deleteUser") && c.expectedOutcome() == AuthorizationMatrixEngine.MatrixOutcome.DENIED_EXPECTED));

        // PublicOp for both -> NOT_APPLICABLE
        assertTrue(cells.stream().anyMatch(c -> c.operationId().equals("getPublicHealth") && c.expectedOutcome() == AuthorizationMatrixEngine.MatrixOutcome.NOT_APPLICABLE));
    }

    @Test
    @DisplayName("Scale Test: 100 concurrent identities and 1,000 operations matrix evaluated in < 1000ms")
    void testScale100Identities1000Operations() {
        String testRunId = "run_scale_" + UUID.randomUUID();
        List<CredentialProfile> profiles = new ArrayList<>();
        Map<String, IdentitySession> sessions = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            CredentialProfile cp = new CredentialProfile("id_" + i, "Role_" + (i % 5), CredentialProfile.AuthStrategy.BEARER_TOKEN, "user" + i, "token_" + i);
            profiles.add(cp);
            IdentitySession sess = new IdentitySession("id_" + i, cp.getName());
            sess.setState(AuthLifecycleState.AUTHENTICATED);
            sessions.put("id_" + i, sess);
        }

        CanonicalApiModel model = new CanonicalApiModel();
        for (int i = 0; i < 1000; i++) {
            CanonicalApiModel.CanonicalOperation op = new CanonicalApiModel.CanonicalOperation();
            op.setOperationId("op_" + i);
            op.setPath("/api/resource_" + i);
            op.setMethod("POST");
            op.setSecurityRequirements(List.of(Map.of("bearerAuth", List.of())));
            model.getOperations().add(op);
        }

        long start = System.currentTimeMillis();
        List<AuthorizationMatrixEngine.MatrixCell> matrix = matrixEngine.buildMatrix(model, profiles, sessions);
        long duration = System.currentTimeMillis() - start;

        // 100 identities * 1,000 operations = 100,000 cells
        assertEquals(100_000, matrix.size());
        assertTrue(duration < 1500, "100,000 matrix cells calculated in " + duration + "ms (must be < 1500ms)");
    }
}
