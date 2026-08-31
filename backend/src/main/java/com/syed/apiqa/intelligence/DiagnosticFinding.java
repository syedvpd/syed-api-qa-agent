package com.syed.apiqa.intelligence;

/**
 * A single structured, rule-derived diagnostic finding attached to a failed or blocked step.
 * Produced deterministically by {@link FailureIntelligenceService} — no external LLM involved.
 */
public class DiagnosticFinding {

    public enum Category {
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

    public enum StepOutcome { FAILED, BLOCKED, TIMEOUT, NETWORK_ERROR }

    private final String stepId;
    private final String stepName;
    private final String method;
    private final String path;
    private final String affectedEndpoint;
    private final Integer responseStatus;
    private final StepOutcome outcome;
    private final Category category;
    private final String severity;
    private final String evidence;
    private final String probableRootCause;
    private final String actionableRemediation;
    private final String upstreamDependency;

    public DiagnosticFinding(String stepId, String stepName, String method, String path,
                             Integer responseStatus, StepOutcome outcome, Category category,
                             String severity, String evidence, String probableRootCause,
                             String actionableRemediation, String upstreamDependency) {
        this.stepId = stepId;
        this.stepName = stepName;
        this.method = method;
        this.path = path;
        this.affectedEndpoint = (method != null ? method + " " : "") + (path != null ? path : "");
        this.responseStatus = responseStatus;
        this.outcome = outcome;
        this.category = category;
        this.severity = severity != null ? severity : "MEDIUM";
        this.evidence = evidence != null ? evidence : "";
        this.probableRootCause = probableRootCause != null ? probableRootCause : "";
        this.actionableRemediation = actionableRemediation != null ? actionableRemediation : "";
        this.upstreamDependency = upstreamDependency != null ? upstreamDependency : "NONE";
    }

    // Backwards-compatible constructor
    public DiagnosticFinding(String stepId, String stepName, String method, String path,
                             Integer responseStatus, StepOutcome outcome, Category category,
                             String diagnosis, String remediation) {
        this(stepId, stepName, method, path, responseStatus, outcome, category,
                category == Category.UNHANDLED_SERVER_CRASH || category == Category.AUTHENTICATION_REQUIRED || category == Category.GATEWAY_OR_BACKEND_TIMEOUT ? "CRITICAL" : "HIGH",
                responseStatus != null ? "HTTP " + responseStatus : "Status: " + outcome,
                diagnosis, remediation, "NONE");
    }

    public String getStepId() { return stepId; }
    public String getStepName() { return stepName; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getAffectedEndpoint() { return affectedEndpoint; }
    public Integer getResponseStatus() { return responseStatus; }
    public StepOutcome getOutcome() { return outcome; }
    public Category getCategory() { return category; }
    public String getSeverity() { return severity; }
    public String getEvidence() { return evidence; }
    public String getProbableRootCause() { return probableRootCause; }
    public String getActionableRemediation() { return actionableRemediation; }
    public String getUpstreamDependency() { return upstreamDependency; }

    // Aliases for compatibility
    public String getDiagnosis() { return probableRootCause; }
    public String getRemediation() { return actionableRemediation; }
    public String getEndpoint() { return affectedEndpoint; }
    public String getRootCause() { return probableRootCause; }
    public Integer getHttpStatus() { return responseStatus; }
}
