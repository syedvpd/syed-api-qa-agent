package com.syed.apiqa.execution;

import com.syed.apiqa.domain.StepStatus;

import java.io.Serializable;

/**
 * Granular Failure Classification for Root-Cause Attribution.
 * Prevents collapsing disparate failures into generic FAILED.
 */
public enum FailureClassification implements Serializable {
    PASSED,
    FAILED,
    BLOCKED,
    SKIPPED,
    UNSUPPORTED,
    NOT_EXECUTED,
    INFRASTRUCTURE_FAILURE,
    AUTHENTICATION_FAILURE,
    AUTHORIZATION_FAILURE,
    SCHEMA_FAILURE,
    REQUEST_GENERATION_FAILURE,
    DEPENDENCY_FAILURE,
    TIMEOUT,
    CONNECTION_FAILURE,
    RETRY_EXHAUSTED,
    RATE_LIMITED;

    /**
     * Map HTTP status code and exception context to granular failure classification.
     */
    public static FailureClassification classify(int statusCode, String errorType, Throwable ex) {
        if (statusCode >= 200 && statusCode < 300) {
            return PASSED;
        }
        if (statusCode == 401) {
            return AUTHENTICATION_FAILURE;
        }
        if (statusCode == 403) {
            return AUTHORIZATION_FAILURE;
        }
        if (statusCode == 422 || "SCHEMA_MISMATCH".equalsIgnoreCase(errorType)) {
            return SCHEMA_FAILURE;
        }
        if (statusCode == 429) {
            return RATE_LIMITED;
        }
        if (statusCode == 504 || "TIMEOUT".equalsIgnoreCase(errorType)) {
            return TIMEOUT;
        }
        if (statusCode == 502 || statusCode == 503 || "NETWORK_ERROR".equalsIgnoreCase(errorType) || "CONNECTION_FAILURE".equalsIgnoreCase(errorType)) {
            return CONNECTION_FAILURE;
        }
        if (statusCode >= 500) {
            return INFRASTRUCTURE_FAILURE;
        }
        if ("REQUEST_NOT_EXECUTABLE".equalsIgnoreCase(errorType) || "GENERATION_FAILURE".equalsIgnoreCase(errorType)) {
            return REQUEST_GENERATION_FAILURE;
        }
        if ("BLOCKED_BY_DEPENDENCY".equalsIgnoreCase(errorType) || "DEPENDENCY_FAILURE".equalsIgnoreCase(errorType)) {
            return DEPENDENCY_FAILURE;
        }
        return FAILED;
    }

    /**
     * Convert to StepStatus for backward compatibility.
     */
    public StepStatus toStepStatus() {
        switch (this) {
            case PASSED: return StepStatus.PASSED;
            case BLOCKED:
            case DEPENDENCY_FAILURE: return StepStatus.BLOCKED;
            case SKIPPED: return StepStatus.SKIPPED;
            case TIMEOUT: return StepStatus.TIMEOUT;
            case RATE_LIMITED: return StepStatus.RATE_LIMITED;
            case AUTHENTICATION_FAILURE: return StepStatus.AUTHENTICATION_ERROR;
            case AUTHORIZATION_FAILURE: return StepStatus.AUTHORIZATION_ERROR;
            case CONNECTION_FAILURE:
            case INFRASTRUCTURE_FAILURE: return StepStatus.NETWORK_ERROR;
            case SCHEMA_FAILURE: return StepStatus.CONTRACT_ERROR;
            case REQUEST_GENERATION_FAILURE:
            case NOT_EXECUTED: return StepStatus.REQUEST_NOT_EXECUTABLE;
            case UNSUPPORTED: return StepStatus.UNSUPPORTED;
            default: return StepStatus.FAILED;
        }
    }
}
