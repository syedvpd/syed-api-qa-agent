package com.syed.apiqa.intelligence;

import com.syed.apiqa.domain.ContractConfidence;

/**
 * A single structured, rule-derived diagnostic finding attached to a failed or blocked step.
 * Produced deterministically by {@link FailureIntelligenceService} — no external LLM involved.
 * Answers the core question: "Is the backend broken or did our QA agent make the request incorrectly?"
 */
public class DiagnosticFinding {

    public enum Category {
        TARGET_API_FAILURE,
        QA_AGENT_REQUEST_GENERATION_FAILURE,
        SCHEMA_GENERATION_FAILURE,
        PARAMETER_GENERATION_FAILURE,
        AUTHENTICATION_FAILURE,
        AUTHORIZATION_DENIAL,
        DEPENDENCY_FAILURE,
        CONTRACT_MISMATCH,
        SPECIFICATION_RUNTIME_MISMATCH,
        NETWORK_FAILURE,
        TIMEOUT,
        RATE_LIMIT,
        UNSUPPORTED_OPERATION,
        INVALID_CONTRACT,
        SAFETY_BLOCK,
        REQUEST_NOT_EXECUTABLE,
        // Backwards compatibility aliases
        AUTHENTICATION_REQUIRED,
        FORBIDDEN_PERMISSIONS,
        RESOURCE_NOT_FOUND,
        STATE_CONFLICT,
        CONTRACT_VALIDATION_ERROR,
        UNHANDLED_SERVER_CRASH,
        GATEWAY_OR_BACKEND_TIMEOUT,
        RATE_LIMIT_EXCEEDED,
        DEPENDENCY_BLOCKED,
        UNKNOWN
    }

    public enum StepOutcome { FAILED, BLOCKED, TIMEOUT, NETWORK_ERROR, SKIPPED }

    public enum Attribution {
        TARGET_API,               // Backend bug: 5xx, or rejecting valid request adhering to spec
        QA_AGENT,                // QA agent bug: invalid generated data, violation of schema pattern
        SPECIFICATION_MISMATCH,  // Discrepancy between OpenAPI contract and runtime behavior (e.g. 200 vs 201)
        INFRASTRUCTURE,          // Network drop, DNS resolution failure, gateway timeout
        SECURITY_POLICY,         // SSRF block or production safety policy
        UNKNOWN
    }

    private final String stepId;
    private final String stepName;
    private final String method;
    private final String path;
    private final String affectedEndpoint;
    private final Integer responseStatus;
    private final StepOutcome outcome;
    private final Category category;
    private final Attribution attribution;
    private final ContractConfidence confidence;
    private final String severity;
    private final String evidence;
    private final String probableRootCause;
    private final String actionableRemediation;
    private final String upstreamDependency;
    private final String blastRadius;

    public DiagnosticFinding(String stepId, String stepName, String method, String path,
                             Integer responseStatus, StepOutcome outcome, Category category,
                             Attribution attribution, ContractConfidence confidence,
                             String severity, String evidence, String probableRootCause,
                             String actionableRemediation, String upstreamDependency,
                             String blastRadius) {
        this.stepId = stepId;
        this.stepName = stepName;
        this.method = method;
        this.path = path;
        this.affectedEndpoint = (method != null ? method + " " : "") + (path != null ? path : "");
        this.responseStatus = responseStatus;
        this.outcome = outcome;
        this.category = category;
        this.attribution = attribution != null ? attribution : Attribution.UNKNOWN;
        this.confidence = confidence != null ? confidence : ContractConfidence.MEDIUM;
        this.severity = severity != null ? severity : "MEDIUM";
        this.evidence = evidence != null ? evidence : "";
        this.probableRootCause = probableRootCause != null ? probableRootCause : "";
        this.actionableRemediation = actionableRemediation != null ? actionableRemediation : "";
        this.upstreamDependency = upstreamDependency != null ? upstreamDependency : "NONE";
        this.blastRadius = blastRadius != null ? blastRadius : "Unaffected independent operations continued";
    }

    // Extended compatibility constructor
    public DiagnosticFinding(String stepId, String stepName, String method, String path,
                             Integer responseStatus, StepOutcome outcome, Category category,
                             String severity, String evidence, String probableRootCause,
                             String actionableRemediation, String upstreamDependency) {
        this(stepId, stepName, method, path, responseStatus, outcome, category,
                mapDefaultAttribution(category), ContractConfidence.HIGH,
                severity, evidence, probableRootCause, actionableRemediation, upstreamDependency,
                "Unaffected independent operations continued");
    }

    // Backwards-compatible constructor
    public DiagnosticFinding(String stepId, String stepName, String method, String path,
                             Integer responseStatus, StepOutcome outcome, Category category,
                             String diagnosis, String remediation) {
        this(stepId, stepName, method, path, responseStatus, outcome, category,
                category == Category.UNHANDLED_SERVER_CRASH || category == Category.TARGET_API_FAILURE ||
                category == Category.AUTHENTICATION_REQUIRED || category == Category.GATEWAY_OR_BACKEND_TIMEOUT ? "CRITICAL" : "HIGH",
                responseStatus != null ? "HTTP " + responseStatus : "Status: " + outcome,
                diagnosis, remediation, "NONE");
    }

    private static Attribution mapDefaultAttribution(Category cat) {
        if (cat == null) return Attribution.UNKNOWN;
        return switch (cat) {
            case TARGET_API_FAILURE, UNHANDLED_SERVER_CRASH -> Attribution.TARGET_API;
            case QA_AGENT_REQUEST_GENERATION_FAILURE, SCHEMA_GENERATION_FAILURE,
                 PARAMETER_GENERATION_FAILURE, REQUEST_NOT_EXECUTABLE -> Attribution.QA_AGENT;
            case CONTRACT_MISMATCH, SPECIFICATION_RUNTIME_MISMATCH -> Attribution.SPECIFICATION_MISMATCH;
            case NETWORK_FAILURE, GATEWAY_OR_BACKEND_TIMEOUT, TIMEOUT -> Attribution.INFRASTRUCTURE;
            case SAFETY_BLOCK -> Attribution.SECURITY_POLICY;
            default -> Attribution.UNKNOWN;
        };
    }

    public String getStepId() { return stepId; }
    public String getStepName() { return stepName; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getAffectedEndpoint() { return affectedEndpoint; }
    public Integer getResponseStatus() { return responseStatus; }
    public StepOutcome getOutcome() { return outcome; }
    public Category getCategory() { return category; }
    public Attribution getAttribution() { return attribution; }
    public ContractConfidence getConfidence() { return confidence; }
    public String getSeverity() { return severity; }
    public String getEvidence() { return evidence; }
    public String getProbableRootCause() { return probableRootCause; }
    public String getActionableRemediation() { return actionableRemediation; }
    public String getUpstreamDependency() { return upstreamDependency; }
    public String getBlastRadius() { return blastRadius; }

    // Aliases for compatibility
    public String getDiagnosis() { return probableRootCause; }
    public String getRemediation() { return actionableRemediation; }
    public String getEndpoint() { return affectedEndpoint; }
    public String getRootCause() { return probableRootCause; }
    public Integer getHttpStatus() { return responseStatus; }
}
