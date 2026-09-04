package com.syed.apiqa.execution;

import com.syed.apiqa.domain.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Production-grade State Machine for Test Step Execution.
 * Strictly validates and enforces legal state transitions, preventing corruptions such as:
 * - PENDING -> PASSED without execution
 * - BLOCKED -> PASSED
 * - RUNNING -> BLOCKED
 * - BLOCKED -> RUNNING
 * - PASSED -> RUNNING
 * - FAILED -> RUNNING without explicit retry
 */
public class ExecutionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateMachine.class);

    private static final Map<StepStatus, Set<StepStatus>> VALID_TRANSITIONS = new EnumMap<>(StepStatus.class);

    static {
        VALID_TRANSITIONS.put(StepStatus.PENDING, EnumSet.of(
                StepStatus.RUNNING,
                StepStatus.BLOCKED,
                StepStatus.SKIPPED,
                StepStatus.REQUEST_NOT_EXECUTABLE,
                StepStatus.UNSUPPORTED
        ));
        VALID_TRANSITIONS.put(StepStatus.RUNNING, EnumSet.of(
                StepStatus.PASSED,
                StepStatus.FAILED,
                StepStatus.WARNING,
                StepStatus.TIMEOUT,
                StepStatus.NETWORK_ERROR,
                StepStatus.AUTHENTICATION_ERROR,
                StepStatus.AUTHORIZATION_ERROR,
                StepStatus.RATE_LIMITED,
                StepStatus.CONTRACT_ERROR,
                StepStatus.CLEANUP_FAILED
        ));
        VALID_TRANSITIONS.put(StepStatus.PASSED, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.BLOCKED, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.SKIPPED, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.FAILED, EnumSet.of(StepStatus.RUNNING)); // Only allowed if explicit retry
        VALID_TRANSITIONS.put(StepStatus.TIMEOUT, EnumSet.of(StepStatus.RUNNING)); // Only allowed if explicit retry
        VALID_TRANSITIONS.put(StepStatus.NETWORK_ERROR, EnumSet.of(StepStatus.RUNNING)); // Only allowed if explicit retry
        VALID_TRANSITIONS.put(StepStatus.RATE_LIMITED, EnumSet.of(StepStatus.RUNNING)); // Allowed on backoff retry
        VALID_TRANSITIONS.put(StepStatus.AUTHENTICATION_ERROR, EnumSet.of(StepStatus.RUNNING)); // Allowed on auth recovery retry
        VALID_TRANSITIONS.put(StepStatus.AUTHORIZATION_ERROR, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.CONTRACT_ERROR, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.REQUEST_NOT_EXECUTABLE, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.UNSUPPORTED, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.CLEANUP_FAILED, EnumSet.noneOf(StepStatus.class));
        VALID_TRANSITIONS.put(StepStatus.UNKNOWN, EnumSet.of(StepStatus.PENDING, StepStatus.RUNNING));
    }

    /**
     * Checks whether a state transition from `current` to `target` is legal.
     */
    public static boolean isValidTransition(StepStatus current, StepStatus target) {
        if (current == null || target == null) return false;
        if (current == target) return true; // Idempotent no-op transition

        Set<StepStatus> allowed = VALID_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(StepStatus.class));
        return allowed.contains(target);
    }

    /**
     * Asserts that a state transition is legal, throwing IllegalStateException on illegal transitions.
     */
    public static void validateTransition(StepStatus current, StepStatus target) {
        if (!isValidTransition(current, target)) {
            String errorMsg = String.format("ILLEGAL STATE TRANSITION: Cannot transition from [%s] to [%s]", current, target);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
    }

    /**
     * Determines whether the given state is terminal.
     */
    public static boolean isTerminal(StepStatus status) {
        if (status == null) return false;
        return status == StepStatus.PASSED ||
                status == StepStatus.FAILED ||
                status == StepStatus.BLOCKED ||
                status == StepStatus.SKIPPED ||
                status == StepStatus.AUTHORIZATION_ERROR ||
                status == StepStatus.CONTRACT_ERROR ||
                status == StepStatus.REQUEST_NOT_EXECUTABLE ||
                status == StepStatus.UNSUPPORTED ||
                status == StepStatus.CLEANUP_FAILED;
    }
}
