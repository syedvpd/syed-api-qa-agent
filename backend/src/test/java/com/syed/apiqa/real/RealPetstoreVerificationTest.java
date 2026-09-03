package com.syed.apiqa.real;

import com.syed.apiqa.domain.Execution;
import com.syed.apiqa.domain.EnvironmentType;
import com.syed.apiqa.domain.RunStatus;
import com.syed.apiqa.domain.StepStatus;
import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.domain.TestStep;
import com.syed.apiqa.persistence.ExecutionRepository;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.run.RunManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class RealPetstoreVerificationTest {

    @Autowired
    private RunManager runManager;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private com.syed.apiqa.persistence.TestStepRepository testStepRepository;

    @Test
    @DisplayName("Gate 5: Real Petstore E2E Verification")
    public void testRealPetstoreE2E() throws Exception {
        String specUrl = "https://petstore.swagger.io/v2/swagger.json";
        TestRun run = new TestRun(UUID.randomUUID().toString(), specUrl, EnvironmentType.STAGING);
        run.setTargetBaseUrl("https://petstore.swagger.io/v2");
        testRunRepository.save(run);

        // Execute run asynchronously without auth profile
        runManager.executeRunAsync(run.getId(), "NONE", null);

        // Wait for execution to complete
        TestRun completedRun = null;
        long deadline = System.currentTimeMillis() + 60000; // 60s timeout for live petstore
        while (System.currentTimeMillis() < deadline) {
            completedRun = testRunRepository.findById(run.getId()).orElseThrow();
            if (completedRun.getStatus() == RunStatus.COMPLETED || completedRun.getStatus() == RunStatus.FAILED) {
                break;
            }
            Thread.sleep(500);
        }

        assertNotNull(completedRun, "Run must not be null");
        System.out.println("PETSTORE RUN STATUS: " + completedRun.getStatus() + ", PASSED: " + completedRun.getPassedTests()
                + ", FAILED: " + completedRun.getFailedTests() + ", BLOCKED: " + completedRun.getBlockedTests());

        List<Execution> executions = executionRepository.findByTestRunId(run.getId());
        assertFalse(executions.isEmpty(), "Petstore execution history should not be empty!");

        boolean hasSuccessfulPublicGet = false;
        boolean hasBlockedAuthPost = false;

        for (Execution exec : executions) {
            TestStep step = exec.getTestStep();
            String method = step != null ? step.getMethod() : exec.getMethod();
            String path = step != null ? step.getPathTemplate() : exec.getRequestUrl();
            
            System.out.println("EXEC: " + method + " " + path + " -> Status: " + exec.getStatus()
                    + ", RespStatus: " + exec.getResponseStatus() + ", ErrorType: " + exec.getErrorType()
                    + ", Latency: " + exec.getLatencyMs() + "ms");

            // Verify at least 1 public GET succeeds (e.g. status == PASSED and responseStatus == 200)
            if (exec.getStatus() == StepStatus.PASSED && "GET".equalsIgnoreCase(method)) {
                hasSuccessfulPublicGet = true;
            }

            // Verify at least 1 POST requires authentication and is blocked
            if ("POST".equalsIgnoreCase(method) && 
                (exec.getStatus() == StepStatus.BLOCKED || "BLOCKED_BY_AUTHENTICATION".equals(exec.getErrorType()))) {
                hasBlockedAuthPost = true;
            }
        }

        List<TestStep> steps = testStepRepository.findByTestCaseTestRunId(run.getId());
        for (TestStep s : steps) {
            System.out.println("STEP: " + s.getMethod() + " " + s.getPathTemplate() + " -> Status: " + s.getStatus()
                    + ", Reason: " + s.getFailureReason());
            if (s.getStatus() == StepStatus.BLOCKED && s.getFailureReason() != null && s.getFailureReason().contains("BLOCKED_BY_AUTHENTICATION")) {
                hasBlockedAuthPost = true;
            }
        }

        System.out.println("Gate 5 Checks: hasSuccessfulPublicGet=" + hasSuccessfulPublicGet + ", hasBlockedAuthPost=" + hasBlockedAuthPost);
        assertTrue(hasSuccessfulPublicGet, "At least 1 public GET must succeed against Petstore");
        assertTrue(hasBlockedAuthPost, "At least 1 POST requiring auth must be BLOCKED_BY_AUTHENTICATION");
    }
}
