package com.syed.apiqa.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.api.TestRunController;
import com.syed.apiqa.domain.EnvironmentType;
import com.syed.apiqa.domain.RunStatus;
import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.domain.TestSchedule;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.persistence.TestScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@TestPropertySource(properties = {
        "syed.security.auth-enabled=true",
        "syed.security.test-mode=false",
        "syed.security.auth-secret=test-production-secret-must-be-long-32bytes!",
        "syed.security.encryption-key=test-encryption-key-for-unit-testing-32bytes!"
})
public class ProductionSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenSecurityService tokenSecurityService;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestScheduleRepository testScheduleRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String tokenAlice;
    private String tokenBob;

    @BeforeEach
    void setUp() {
        tokenAlice = "Bearer " + tokenSecurityService.issueToken("user-alice", Duration.ofHours(2));
        tokenBob = "Bearer " + tokenSecurityService.issueToken("user-bob", Duration.ofHours(2));
    }

    @Test
    void unauthenticatedRequestShouldBeRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void invalidTokenShouldBeRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .header("Authorization", "Bearer invalid.token.payload"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void forgedIdentityHeaderMismatchingTokenShouldBeRejectedWith403() throws Exception {
        // Alice provides valid token for Alice, but maliciously attempts to claim X-User-Id: user-bob
        mockMvc.perform(get("/api/runs")
                        .header("Authorization", tokenAlice)
                        .header("X-User-Id", "user-bob"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORGED_IDENTITY"));
    }

    @Test
    void authenticatedUserCanAccessOwnResource() throws Exception {
        TestRun runAlice = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        runAlice.setOwnerId("user-alice");
        runAlice.setStatus(RunStatus.COMPLETED);
        testRunRepository.save(runAlice);

        mockMvc.perform(get("/api/runs/" + runAlice.getId())
                        .header("Authorization", tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runAlice.getId()))
                .andExpect(jsonPath("$.ownerId").value("user-alice"));
    }

    @Test
    void authenticatedUserCannotAccessOtherUsersResource() throws Exception {
        TestRun runBob = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        runBob.setOwnerId("user-bob");
        runBob.setStatus(RunStatus.COMPLETED);
        testRunRepository.save(runBob);

        // Alice attempts to access Bob's test run
        mockMvc.perform(get("/api/runs/" + runBob.getId())
                        .header("Authorization", tokenAlice))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCannotCrossTenantCompareRegressionBaseline() throws Exception {
        TestRun runAlice = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        runAlice.setOwnerId("user-alice");
        runAlice.setStatus(RunStatus.COMPLETED);
        testRunRepository.save(runAlice);

        TestRun runBob = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        runBob.setOwnerId("user-bob");
        runBob.setStatus(RunStatus.COMPLETED);
        testRunRepository.save(runBob);

        // Alice tries to run regression compare against Bob's run as baseline
        mockMvc.perform(post("/api/runs/" + runAlice.getId() + "/regression/compare")
                        .param("baselineId", runBob.getId())
                        .header("Authorization", tokenAlice))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Cannot compare with baseline owned by another user."));
    }

    @Test
    void secretsDoNotAppearInSerializedGetResponses() throws Exception {
        TestRun run = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        run.setOwnerId("user-alice");
        run.setAuthLoginPayload("{\"password\":\"super-secret-12345\"}");
        testRunRepository.save(run);

        mockMvc.perform(get("/api/runs/" + run.getId())
                        .header("Authorization", tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authLoginPayload").doesNotExist());
    }

    @Test
    void scheduleAuthTokensDoNotAppearInSerializedResponses() throws Exception {
        TestSchedule schedule = new TestSchedule(
                "user-alice", "Alice Schedule", "https://petstore.swagger.io/v2/swagger.json",
                "STAGING", TestSchedule.ScheduleType.DAILY, null
        );
        schedule.setAuthToken("secret-bearer-token-12345");
        testScheduleRepository.save(schedule);

        mockMvc.perform(get("/api/schedules/" + schedule.getId())
                        .header("Authorization", tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authToken").doesNotExist());
    }

    @Test
    void authControllerGeneratesValidSignedToken() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", "new-user-999"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value("new-user-999"));
    }

    @Test
    void expiredTokenShouldBeRejectedWith401() throws Exception {
        String expired = "Bearer " + tokenSecurityService.issueToken("user-alice", Duration.ofMillis(1));
        Thread.sleep(1100);

        mockMvc.perform(get("/api/runs")
                        .header("Authorization", expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")));
    }

    @Test
    void forgedTokenSignatureShouldBeRejectedWith401() throws Exception {
        String validToken = tokenSecurityService.issueToken("user-alice", Duration.ofHours(1));
        int lastDot = validToken.lastIndexOf('.');
        String forgedToken = "Bearer " + validToken.substring(0, lastDot + 1) + "forgedSignatureBase64String12345=";

        mockMvc.perform(get("/api/runs")
                        .header("Authorization", forgedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("verification failed")));
    }

    @Test
    void userCannotStreamOtherUsersSseEvents() throws Exception {
        TestRun runBob = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        runBob.setOwnerId("user-bob");
        testRunRepository.save(runBob);

        // Alice tries to subscribe to Bob's event stream
        mockMvc.perform(get("/api/runs/" + runBob.getId() + "/events")
                        .header("Authorization", tokenAlice))
                .andExpect(request().asyncStarted());
    }

    @Test
    void databaseStoresEncryptedPayloadsDirectly() {
        TestRun run = new TestRun(UUID.randomUUID().toString(), "https://petstore.swagger.io/v2/swagger.json", EnvironmentType.STAGING);
        run.setOwnerId("user-alice");
        String rawPassword = "ultra-secret-db-password-999";
        run.setAuthLoginPayload(rawPassword);
        TestRun saved = testRunRepository.saveAndFlush(run);

        // Verify that in-memory entity decrypts transparently
        assertEquals(rawPassword, saved.getAuthLoginPayload());
    }
}
