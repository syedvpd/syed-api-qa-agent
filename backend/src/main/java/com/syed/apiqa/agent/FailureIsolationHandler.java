package com.syed.apiqa.agent;

import com.syed.apiqa.domain.StepStatus;
import com.syed.apiqa.domain.TestStep;
import com.syed.apiqa.persistence.TestStepRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles dependency-aware failure isolation.
 * When an upstream step fails or times out, downstream dependent steps
 * in the workflow are safely marked BLOCKED, while independent test branches proceed.
 */
@Service
public class FailureIsolationHandler {

    private final TestStepRepository testStepRepository;

    public FailureIsolationHandler(TestStepRepository testStepRepository) {
        this.testStepRepository = testStepRepository;
    }

    public int isolateFailureAndBlockDownstream(TestStep failedStep, List<TestStep> remainingStepsInCase) {
        String scenarioType = null;
        try {
            if (failedStep.getTestCase() != null) {
                scenarioType = failedStep.getTestCase().getScenarioType();
            }
        } catch (Exception ignored) {}
        return isolateFailureAndBlockDownstream(failedStep, remainingStepsInCase, scenarioType);
    }

    public int isolateFailureAndBlockDownstream(TestStep failedStep, List<TestStep> remainingStepsInCase, String scenarioType) {
        int blockedCount = 0;

        // In independent scenarios (like NEGATIVE_ROBUSTNESS or SINGLE_ENDPOINT), variants are isolated and should not cascade blocks
        if (scenarioType != null && !"CRUD_WORKFLOW".equalsIgnoreCase(scenarioType)) {
            return 0;
        }

        for (TestStep step : remainingStepsInCase) {
            if (step.getStatus() == StepStatus.PENDING) {
                step.setStatus(StepStatus.BLOCKED);
                step.setFailureReason("BLOCKED: Upstream prerequisite step '" + failedStep.getName() +
                        "' encountered " + failedStep.getStatus() + ".");
                testStepRepository.save(step);
                blockedCount++;
            }
        }

        return blockedCount;
    }
}
