package com.syed.apiqa;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.syed.apiqa.api.ScheduleController;
import com.syed.apiqa.api.TestRunController;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.run.RunManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase6RunControlAndSchedulingTest {

    private static WireMockServer wireMockServer;
    private static String baseUrl;

    @Autowired
    private RunManager runManager;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestScheduleRepository testScheduleRepository;

    @Autowired
    private RunAuditEventRepository auditEventRepository;

    @Autowired
    private TestRunController testRunController;

    @Autowired
    private ScheduleController scheduleController;

    @Autowired
    private com.syed.apiqa.safety.SsrfProtectionGuard ssrfGuard;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .bindAddress("127.0.0.1")
                .dynamicPort());
        wireMockServer.start();
        baseUrl = "http://127.0.0.1:" + wireMockServer.port();

        String specJson = "{\n" +
                "  \"openapi\": \"3.0.1\",\n" +
                "  \"info\": { \"title\": \"Run Control & Scheduling API\", \"version\": \"6.0.0\" },\n" +
                "  \"servers\": [{ \"url\": \"" + baseUrl + "\" }],\n" +
                "  \"paths\": {\n" +
                "    \"/tasks\": {\n" +
                "      \"get\": { \"summary\": \"List Tasks\", \"responses\": { \"200\": { \"description\": \"OK\" } } }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        wireMockServer.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(specJson)));

        wireMockServer.stubFor(get(urlEqualTo("/tasks"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(20)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"task\":\"Clean database\"}]")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testIdempotentRunStartProtection() {
        String specUrl = baseUrl + "/v3/api-docs";
        String idempotencyKey = "idemp-key-" + UUID.randomUUID();

        Map<String, String> request = Map.of(
                "openapiUrl", specUrl,
                "environmentType", "STAGING"
        );

        // 1. First submission
        ResponseEntity<?> res1 = testRunController.createAndLaunchRun(request, idempotencyKey, "user_ops", null);
        assertEquals(201, res1.getStatusCode().value());
        Map<String, Object> body1 = (Map<String, Object>) res1.getBody();
        String runId1 = (String) body1.get("runId");
        assertNotNull(runId1);

        // 2. Duplicate submission with identical key
        ResponseEntity<?> res2 = testRunController.createAndLaunchRun(request, idempotencyKey, "user_ops", null);
        assertEquals(200, res2.getStatusCode().value(), "Idempotent retry must return 200 OK with existing run");
        TestRun run2 = (TestRun) res2.getBody();
        assertEquals(runId1, run2.getId(), "Idempotent retry must not spawn duplicate run");
    }

    @Test
    void testRunPauseResumeAndCancelControls() {
        String specUrl = baseUrl + "/v3/api-docs";
        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        run.setOwnerId("user_controller");
        run.setStatus(RunStatus.EXECUTING);
        testRunRepository.save(run);

        // 1. Pause Run
        ResponseEntity<?> pauseRes = testRunController.pauseRun(run.getId(), "user_controller", null);
        assertEquals(200, pauseRes.getStatusCode().value());
        TestRun pausedRun = testRunRepository.findById(run.getId()).orElseThrow();
        assertEquals(RunStatus.PAUSED, pausedRun.getStatus());
        assertTrue(runManager.isPaused(run.getId()));

        // Unauthorized pause attempt
        ResponseEntity<?> unauthorizedPause = testRunController.pauseRun(run.getId(), "attacker_user", null);
        assertEquals(403, unauthorizedPause.getStatusCode().value());

        // 2. Resume Run
        ResponseEntity<?> resumeRes = testRunController.resumeRun(run.getId(), "user_controller", null);
        assertEquals(200, resumeRes.getStatusCode().value());
        TestRun resumedRun = testRunRepository.findById(run.getId()).orElseThrow();
        assertEquals(RunStatus.EXECUTING, resumedRun.getStatus());
        assertFalse(runManager.isPaused(run.getId()));

        // 3. Cancel Run
        ResponseEntity<?> cancelRes = testRunController.cancelRun(run.getId(), Map.of("reason", "Operator emergency stop"), "user_controller", null);
        assertEquals(200, cancelRes.getStatusCode().value());
        TestRun cancelledRun = testRunRepository.findById(run.getId()).orElseThrow();
        assertEquals(RunStatus.CANCELLED, cancelledRun.getStatus());
        assertEquals("Operator emergency stop", cancelledRun.getCancellationReason());
        assertTrue(runManager.isCancelled(run.getId()));

        // 4. Audit Trail Verification
        ResponseEntity<?> auditRes = testRunController.getAuditEvents(run.getId(), "user_controller", null);
        assertEquals(200, auditRes.getStatusCode().value());
        List<RunAuditEvent> events = (List<RunAuditEvent>) auditRes.getBody();
        assertFalse(events.isEmpty(), "Lifecycle audit trail must capture transitions");
        assertTrue(events.stream().anyMatch(e -> "PAUSED".equals(e.getEventType())));
        assertTrue(events.stream().anyMatch(e -> "RESUMED".equals(e.getEventType())));
        assertTrue(events.stream().anyMatch(e -> "CANCELLED".equals(e.getEventType())));
    }

    @Test
    void testCrashRecoveryOnStartup() {
        String specUrl = baseUrl + "/v3/api-docs";
        TestRun lingeringRun = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        lingeringRun.setStatus(RunStatus.EXECUTING);
        testRunRepository.save(lingeringRun);

        // Simulate application restart
        runManager.recoverLingeringRunsOnStartup();

        TestRun recoveredRun = testRunRepository.findById(lingeringRun.getId()).orElseThrow();
        assertEquals(RunStatus.FAILED, recoveredRun.getStatus());
        assertEquals("BACKEND_RESTART_DURING_EXECUTION", recoveredRun.getErrorMessage());
    }

    @Test
    void testScheduleManagementAndSSRFGuard() {
        // 1. SSRF Violation in Schedule Creation must be blocked
        org.springframework.test.util.ReflectionTestUtils.setField(ssrfGuard, "ssrfProtectionEnabled", true);
        try {
            Map<String, Object> invalidSchedule = Map.of(
                    "name", "Malicious Schedule",
                    "openapiUrl", "http://169.254.169.254/latest/meta-data",
                    "environment", "STAGING",
                    "scheduleType", "DAILY"
            );
            ResponseEntity<?> ssrfBlockRes = scheduleController.createSchedule(invalidSchedule, "user_sched", null);
            assertEquals(400, ssrfBlockRes.getStatusCode().value());
            assertTrue(((Map<?, ?>) ssrfBlockRes.getBody()).get("error").toString().contains("SSRF"));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(ssrfGuard, "ssrfProtectionEnabled", false);
        }

        // 2. Valid Schedule Creation
        String specUrl = baseUrl + "/v3/api-docs";
        Map<String, Object> validSchedule = Map.of(
                "name", "Nightly QA Sweep",
                "openapiUrl", specUrl,
                "environment", "STAGING",
                "scheduleType", "DAILY"
        );
        ResponseEntity<?> createRes = scheduleController.createSchedule(validSchedule, "user_sched", null);
        assertEquals(201, createRes.getStatusCode().value());
        TestSchedule createdSchedule = (TestSchedule) createRes.getBody();
        assertNotNull(createdSchedule.getId());
        assertTrue(createdSchedule.isEnabled());

        // 3. Toggle Schedule
        ResponseEntity<?> toggleRes = scheduleController.toggleSchedule(createdSchedule.getId(), "user_sched", null);
        assertEquals(200, toggleRes.getStatusCode().value());
        TestSchedule toggled = (TestSchedule) toggleRes.getBody();
        assertFalse(toggled.isEnabled(), "Schedule must now be disabled");

        // 4. Unauthorized User blocked on Schedule Delete
        ResponseEntity<?> unauthorizedDel = scheduleController.deleteSchedule(createdSchedule.getId(), "attacker_user", null);
        assertEquals(403, unauthorizedDel.getStatusCode().value());

        // 5. Authorized Run Now
        ResponseEntity<?> runNowRes = scheduleController.runScheduleNow(createdSchedule.getId(), "user_sched", null);
        assertEquals(200, runNowRes.getStatusCode().value());
        Map<?, ?> runNowBody = (Map<?, ?>) runNowRes.getBody();
        assertNotNull(runNowBody.get("runId"));

        // 6. Authorized Delete
        ResponseEntity<?> deleteRes = scheduleController.deleteSchedule(createdSchedule.getId(), "user_sched", null);
        assertEquals(204, deleteRes.getStatusCode().value());
        assertTrue(testScheduleRepository.findById(createdSchedule.getId()).isEmpty());
    }
}
