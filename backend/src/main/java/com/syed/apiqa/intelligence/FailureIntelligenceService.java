package com.syed.apiqa.intelligence;

import com.syed.apiqa.domain.*;
import com.syed.apiqa.domain.ContractConfidence;
import com.syed.apiqa.intelligence.DiagnosticFinding.Attribution;
import com.syed.apiqa.intelligence.DiagnosticFinding.Category;
import com.syed.apiqa.intelligence.DiagnosticFinding.StepOutcome;
import com.syed.apiqa.persistence.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-Based Failure Intelligence Engine (Heart #5 of Syed API QA Agent).
 * Deterministically classifies failed, blocked, or withheld steps into root-cause categories
 * answering the fundamental question: "Is the backend broken or did our QA agent make the request incorrectly?"
 * Produces structured findings with exact attribution, evidence, confidence, and failure containment blast radius.
 */
@Service
public class FailureIntelligenceService {

    private final TestCaseRepository testCaseRepository;
    private final TestStepRepository testStepRepository;
    private final ExecutionRepository executionRepository;

    public FailureIntelligenceService(TestCaseRepository testCaseRepository,
                                      TestStepRepository testStepRepository,
                                      ExecutionRepository executionRepository) {
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
    }

    /**
     * Analyzes every non-passed step in the run and produces structured diagnostic findings.
     */
    public List<DiagnosticFinding> analyzeRun(TestRun run) {
        List<DiagnosticFinding> findings = new ArrayList<>();

        Map<String, Execution> stepExecutionMap = new HashMap<>();
        List<String[]> failedCreateCandidates = new ArrayList<>();

        List<Execution> executions = executionRepository.findByTestRunId(run.getId());
        for (Execution exec : executions) {
            if (exec.getTestStep() != null) {
                stepExecutionMap.put(exec.getTestStep().getId(), exec);
            }
        }

        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(run.getId());
        int totalRunSteps = 0;
        for (TestCase tc : cases) {
            totalRunSteps += testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId()).size();
        }

        for (TestCase tc : cases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            for (int i = 0; i < steps.size(); i++) {
                TestStep step = steps.get(i);
                String method = step.getMethod();
                String path = step.getPathTemplate() != null ? step.getPathTemplate() : step.getResolvedUrl();

                if (!isFailureOutcome(step.getStatus())) {
                    continue;
                }

                if ("POST".equalsIgnoreCase(method)) {
                    failedCreateCandidates.add(new String[]{step.getId(), extractEntity(path)});
                }

                Execution exec = stepExecutionMap.get(step.getId());
                Integer responseStatus = (exec != null) ? exec.getResponseStatus() : null;

                int affectedInCase = steps.size() - (i + 1);
                int unaffectedInRun = Math.max(0, totalRunSteps - (affectedInCase + 1));
                String blastRadius = String.format("Blast Radius: 1 failure blocked %d dependent steps in workflow; %d independent operations continued",
                        affectedInCase, unaffectedInRun);

                DiagnosticFinding finding = classify(step, method, path, responseStatus,
                        step.getStatus(), exec, blastRadius);
                findings.add(finding);
            }
        }

        correlateMissingResources(findings, failedCreateCandidates);
        return findings;
    }

    private boolean isFailureOutcome(StepStatus status) {
        return status == StepStatus.FAILED
                || status == StepStatus.BLOCKED
                || status == StepStatus.TIMEOUT
                || status == StepStatus.NETWORK_ERROR
                || status == StepStatus.AUTHENTICATION_ERROR
                || status == StepStatus.AUTHORIZATION_ERROR
                || status == StepStatus.CONTRACT_ERROR
                || status == StepStatus.REQUEST_NOT_EXECUTABLE;
    }

    /**
     * Public method to diagnose a single step and execution in real-time.
     */
    public DiagnosticFinding diagnoseStep(TestStep step, Execution exec) {
        String method = step.getMethod() != null ? step.getMethod() : "GET";
        String path = step.getPathTemplate() != null ? step.getPathTemplate() : (step.getResolvedUrl() != null ? step.getResolvedUrl() : "/");
        Integer status = exec != null ? exec.getResponseStatus() : null;
        return classify(step, method, path, status, step.getStatus(), exec, "Unaffected independent operations continued");
    }

    /**
     * Maps an observable step outcome + HTTP status to root-cause attribution, confidence, and remediation.
     */
    public DiagnosticFinding classify(TestStep step, String method, String path, Integer status,
                                      StepStatus stepStatus, Execution exec, String blastRadius) {
        StepOutcome outcome = toOutcome(stepStatus);
        String reason = step.getFailureReason() != null ? step.getFailureReason() : "";

        // 1. Pre-Request Gate Check (Withheld from network)
        if (stepStatus == StepStatus.REQUEST_NOT_EXECUTABLE) {
            return new DiagnosticFinding(
                    step.getId(), step.getName(), method, path, null, outcome,
                    Category.REQUEST_NOT_EXECUTABLE,
                    Attribution.QA_AGENT,
                    ContractConfidence.HIGH,
                    "MEDIUM",
                    "Pre-Request Gate: " + sanitize(reason),
                    "Request was withheld by the Pre-Request Contract Gate because it violated contract prerequisites (" + sanitize(reason) + "). No bad HTTP request was sent to the server.",
                    "Ensure upstream variables or dependencies are satisfied before dispatching dependent endpoints.",
                    "UPSTREAM_GATE",
                    blastRadius
            );
        }

        // 2. Auth Failure Cascade Check
        if (stepStatus == StepStatus.BLOCKED && reason.contains("BLOCKED_BY_AUTHENTICATION")) {
            return new DiagnosticFinding(
                    step.getId(), step.getName(), method, path, null, outcome,
                    Category.AUTHENTICATION_FAILURE,
                    Attribution.QA_AGENT,
                    ContractConfidence.HIGH,
                    "CRITICAL",
                    reason,
                    "Step was blocked prior to network dispatch because the identity failed authentication during preflight.",
                    "Verify user credentials, login endpoint response fields, or token refresh URL in configuration.",
                    "AUTH_PREFLIGHT",
                    blastRadius
            );
        }

        // 3. Upstream Dependency Failure
        if (stepStatus == StepStatus.BLOCKED) {
            return new DiagnosticFinding(
                    step.getId(), step.getName(), method, path, status, outcome,
                    Category.DEPENDENCY_FAILURE,
                    Attribution.QA_AGENT,
                    ContractConfidence.HIGH,
                    "HIGH",
                    "Upstream failure: " + sanitize(reason),
                    "Step was blocked because a prerequisite upstream step failed earlier in the workflow.",
                    "Inspect the upstream dependency step in this test case; once the prerequisite passes, this step will unblock.",
                    "UPSTREAM_STEP",
                    blastRadius
            );
        }

        // 4. Status is null (Transport, timeout, or network)
        if (status == null) {
            if (outcome == StepOutcome.TIMEOUT) {
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, null, outcome,
                        Category.TIMEOUT,
                        Attribution.TARGET_API,
                        ContractConfidence.HIGH,
                        "CRITICAL",
                        "Socket Timeout: " + sanitize(reason),
                        "The request exceeded the configured timeout window with no response from the target server.",
                        "Verify backend handles this endpoint within SLA; investigate slow DB queries or hung worker threads.",
                        "NONE",
                        blastRadius);
            }
            return new DiagnosticFinding(step.getId(), step.getName(), method, path, null, outcome,
                    Category.NETWORK_FAILURE,
                    Attribution.INFRASTRUCTURE,
                    ContractConfidence.HIGH,
                    "HIGH",
                    "Network Dispatch Error: " + sanitize(reason),
                    "Network transport error encountered while connecting to target.",
                    "Check connectivity, DNS, TLS certificates, and proxy routing to the target API.",
                    "NONE",
                    blastRadius);
        }

        // 5. Server Crashes (5xx) -> Definite Target API Failure
        if (status >= 500) {
            return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                    Category.TARGET_API_FAILURE,
                    Attribution.TARGET_API,
                    ContractConfidence.HIGH,
                    "CRITICAL",
                    "HTTP " + status + " Server Error: " + sanitize(reason),
                    "Endpoint returned HTTP " + status + " — an unhandled server-side crash. The request was preflight-validated and conformed to contract constraints, indicating a target backend defect.",
                    "Inspect backend application logs for stack traces, unhandled null pointers, or uncaught SQL exceptions.",
                    "NONE",
                    blastRadius);
        }

        // 6. Client Errors (4xx) -> Differentiate Agent Error vs Contract Mismatch vs Auth
        switch (status) {
            case 401:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.AUTHENTICATION_FAILURE,
                        Attribution.QA_AGENT,
                        ContractConfidence.HIGH,
                        "CRITICAL",
                        "HTTP 401 Unauthorized: Token or API key rejected by target",
                        "Endpoint rejected credentials with HTTP 401. Authentication token was invalid, expired, or missing.",
                        "Verify token expiration, API key header name, or dynamic login endpoint payload.",
                        "AUTH_IDENTITY",
                        blastRadius);

            case 403:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.AUTHORIZATION_DENIAL,
                        Attribution.TARGET_API,
                        ContractConfidence.HIGH,
                        "HIGH",
                        "HTTP 403 Forbidden: Identity lacks permission for operation",
                        "Endpoint returned HTTP 403 — the identity is authenticated but lacks permission for this operation. If testing negative RBAC, this is an expected denial.",
                        "Grant the required role/scope for this operation if the identity was expected to have access.",
                        "RBAC_ROLE",
                        blastRadius);

            case 404:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.RESOURCE_NOT_FOUND,
                        Attribution.QA_AGENT,
                        ContractConfidence.MEDIUM,
                        "MEDIUM",
                        "HTTP 404 Not Found: Resource does not exist",
                        "Endpoint returned HTTP 404. Verify whether the expected resource was ever created or whether the variable was captured.",
                        "Confirm the upstream CREATE step actually ran and returned an ID, and that the extracted variable was populated.",
                        "UPSTREAM_CREATE",
                        blastRadius);

            case 409:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.STATE_CONFLICT,
                        Attribution.TARGET_API,
                        ContractConfidence.HIGH,
                        "MEDIUM",
                        "HTTP 409 Conflict: Unique constraint or optimistic lock violation",
                        "Endpoint returned HTTP 409 — a state conflict occurred (duplicate key or concurrent race condition).",
                        "Check for duplicate-key creation or concurrent writes to the same resource.",
                        "NONE",
                        blastRadius);

            case 400:
            case 422:
                // Check if request violated schema constraints
                boolean isAgentConstraintViolation = reason.toLowerCase().contains("pattern")
                        || reason.toLowerCase().contains("format")
                        || reason.toLowerCase().contains("missing required")
                        || reason.toLowerCase().contains("type mismatch");

                if (isAgentConstraintViolation) {
                    return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                            Category.QA_AGENT_REQUEST_GENERATION_FAILURE,
                            Attribution.QA_AGENT,
                            ContractConfidence.HIGH,
                            "HIGH",
                            "HTTP " + status + ": Generated value violated schema constraint (" + sanitize(reason) + ")",
                            "The QA Agent generated a request that violated declared schema constraints (e.g. regex pattern, min/max, format).",
                            "Enhance data generator constraint adherence for this schema property type.",
                            "DATA_GENERATOR",
                            blastRadius);
                } else {
                    return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                            Category.SPECIFICATION_RUNTIME_MISMATCH,
                            Attribution.SPECIFICATION_MISMATCH,
                            ContractConfidence.MEDIUM,
                            "HIGH",
                            "HTTP " + status + ": Request complied with OpenAPI schema but was rejected by runtime",
                            "Endpoint returned HTTP " + status + ", but the request conformed to the declared OpenAPI schema. The API specification is missing validation rules or runtime requirements.",
                            "Update the OpenAPI specification with undocumented validation constraints or required fields.",
                            "CONTRACT_SPEC",
                            blastRadius);
                }

            case 429:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.RATE_LIMIT,
                        Attribution.TARGET_API,
                        ContractConfidence.HIGH,
                        "MEDIUM",
                        "HTTP 429 Rate Limit Exceeded",
                        "Endpoint throttled request with HTTP 429.",
                        "Honor Retry-After header and reduce concurrency limiter settings in runner configuration.",
                        "NONE",
                        blastRadius);

            default:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.UNKNOWN,
                        Attribution.UNKNOWN,
                        ContractConfidence.LOW,
                        "MEDIUM",
                        "HTTP " + status + ": " + sanitize(reason),
                        "Unclassified outcome with HTTP " + status + ". Evidence: " + sanitize(reason),
                        "Review the captured response body and reconcile with expected status contract in the OpenAPI spec.",
                        "NONE",
                        blastRadius);
        }
    }

    private void correlateMissingResources(List<DiagnosticFinding> findings,
                                           List<String[]> failedCreateCandidates) {
        for (int i = 0; i < findings.size(); i++) {
            DiagnosticFinding finding = findings.get(i);
            if (finding.getCategory() != Category.RESOURCE_NOT_FOUND) {
                continue;
            }
            String entity = extractEntity(finding.getPath());
            if (entity == null) {
                continue;
            }
            boolean createFailed = failedCreateCandidates.stream()
                    .anyMatch(c -> entity.equalsIgnoreCase(c[1]));
            if (!createFailed) {
                continue;
            }
            findings.set(i,
                    new DiagnosticFinding(
                            finding.getStepId(), finding.getStepName(), finding.getMethod(),
                            finding.getPath(), finding.getResponseStatus(), finding.getOutcome(),
                            Category.DEPENDENCY_FAILURE,
                            Attribution.QA_AGENT,
                            ContractConfidence.HIGH,
                            "HIGH",
                            "HTTP 404 downstream of failed CREATE for entity '" + entity + "'",
                            "HTTP 404 while reading/updating entity '" + entity + "'. The corresponding CREATE step failed earlier in this run, so the resource was never created in the backend.",
                            "Resolve the upstream CREATE failure first, then re-run so the resource is created before dependent GET/DELETE operations.",
                            "UPSTREAM_CREATE_" + entity.toUpperCase(),
                            finding.getBlastRadius())
            );
        }
    }

    private String extractEntity(String path) {
        if (path == null) return null;
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        int slash = trimmed.indexOf('/');
        if (slash > 0) trimmed = trimmed.substring(0, slash);
        return trimmed.isBlank() ? null : trimmed;
    }

    private StepOutcome toOutcome(StepStatus status) {
        if (status == null) return StepOutcome.FAILED;
        return switch (status) {
            case BLOCKED -> StepOutcome.BLOCKED;
            case TIMEOUT -> StepOutcome.TIMEOUT;
            case NETWORK_ERROR -> StepOutcome.NETWORK_ERROR;
            case SKIPPED -> StepOutcome.SKIPPED;
            default -> StepOutcome.FAILED;
        };
    }

    private String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\n\\r]+", " ").trim();
    }
}
