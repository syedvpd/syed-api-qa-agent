package com.syed.apiqa.intelligence;

import com.syed.apiqa.domain.*;
import com.syed.apiqa.intelligence.DiagnosticFinding.Category;
import com.syed.apiqa.intelligence.DiagnosticFinding.StepOutcome;
import com.syed.apiqa.persistence.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-Based Failure Intelligence Engine.
 * Deterministically classifies failed or blocked steps into root-cause categories and
 * attaches actionable remediation suggestions. Uses only persisted HTTP evidence and the
 * dependency graph — it never consults an external LLM.
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
     * Analyzes every failed, blocked, timed-out, or network-errored step in the run and
     * produces structured diagnostic findings.
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
        for (TestCase tc : cases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            for (TestStep step : steps) {
                String method = step.getMethod();
                String path = step.getPathTemplate() != null ? step.getPathTemplate() : step.getResolvedUrl();

                if (!isFailureOutcome(step.getStatus())) {
                    continue;
                }

                // Track failed POST steps so downstream 404 reads can be correlated back to a
                // failed create of the same entity. The step itself is still classified below.
                if ("POST".equalsIgnoreCase(method)) {
                    failedCreateCandidates.add(new String[]{step.getId(), extractEntity(path)});
                }

                Execution exec = stepExecutionMap.get(step.getId());
                Integer responseStatus = (exec != null) ? exec.getResponseStatus() : null;

                DiagnosticFinding finding = classify(step, method, path, responseStatus,
                        step.getStatus(), exec);
                findings.add(finding);
            }
        }

        // Correlate 404s on reads/updates with a failed upstream CREATE of the same entity.
        correlateMissingResources(findings, failedCreateCandidates);

        return findings;
    }

    private boolean isFailureOutcome(StepStatus status) {
        return status == StepStatus.FAILED
                || status == StepStatus.BLOCKED
                || status == StepStatus.TIMEOUT
                || status == StepStatus.NETWORK_ERROR
                || status == StepStatus.AUTHENTICATION_ERROR;
    }

    /**
     * Public method to diagnose a single step and execution.
     */
    public DiagnosticFinding diagnoseStep(TestStep step, Execution exec) {
        String method = step.getMethod() != null ? step.getMethod() : "GET";
        String path = step.getPathTemplate() != null ? step.getPathTemplate() : (step.getResolvedUrl() != null ? step.getResolvedUrl() : "/");
        Integer status = exec != null ? exec.getResponseStatus() : null;
        return classify(step, method, path, status, step.getStatus(), exec);
    }

    /**
     * Maps an observable step outcome + HTTP status to a root-cause category and remediation.
     */
    DiagnosticFinding classify(TestStep step, String method, String path, Integer status,
                               StepStatus stepStatus, Execution exec) {
        StepOutcome outcome = toOutcome(stepStatus);

        if (stepStatus == StepStatus.BLOCKED) {
            return new DiagnosticFinding(
                    step.getId(), step.getName(), method, path, status, outcome,
                    Category.DEPENDENCY_BLOCKED,
                    "Step was blocked because a prerequisite step failed earlier in the workflow.",
                    "Inspect the upstream dependency step in this test case; once the prerequisite "
                            + "passes, this step will unblock. If it is truly independent, split it "
                            + "into its own test case so a single failure does not cascade."
            );
        }

        if (status == null) {
            if (outcome == StepOutcome.TIMEOUT) {
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, null, outcome,
                        Category.GATEWAY_OR_BACKEND_TIMEOUT,
                        "The request exceeded the configured timeout and produced no status code.",
                        "Verify the backend handles this endpoint within the SLA window; investigate "
                                + "slow database queries, blocking downstream calls, or a hung worker thread.");
            }
            return new DiagnosticFinding(step.getId(), step.getName(), method, path, null, outcome,
                    Category.UNKNOWN,
                    "No HTTP status was captured for this step (network or transport failure).",
                    "Check connectivity, TLS, DNS, and proxy configuration between the agent and the target.");
        }

        String reason = step.getFailureReason() != null ? step.getFailureReason() : "";

        switch (status) {
            case 401:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.AUTHENTICATION_REQUIRED,
                        "Endpoint returned HTTP 401 — the supplied authentication was rejected or absent.",
                        "Verify the Bearer token / API key / credentials are valid and not expired. If "
                                + "using dynamic auth, confirm the login payload reaches the auth endpoint "
                                + "and that token refresh is configured on 401.");
            case 403:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.FORBIDDEN_PERMISSIONS,
                        "Endpoint returned HTTP 403 — the identity is valid but lacks permission for this operation.",
                        "Grant the required role/scope or service-account permission for this operation on the "
                                + "target environment.");
            case 404:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.RESOURCE_NOT_FOUND,
                        "Endpoint returned HTTP 404 — resource not found. Verify whether the expected "
                                + "resource was ever created or whether the variable was captured.",
                        "Confirm the upstream CREATE step actually ran and returned an ID, and that the "
                                + "extracted variable is present. Check path/id handling for typos or the "
                                + "wrong identifier being interpolated.");
            case 409:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.STATE_CONFLICT,
                        "Endpoint returned HTTP 409 — a state conflict occurred (duplicate key or race condition).",
                        "Check for duplicate-key creation, concurrent writes to the same resource, or a "
                                + "stale optimistic-lock version. Idempotency keys may be required.");
            case 400:
            case 422:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.CONTRACT_VALIDATION_ERROR,
                        "Endpoint rejected the request payload with HTTP " + status
                                + " — a required attribute was missing, malformed, or out of contract.",
                        "Compare the generated request body against the OpenAPI schema. Pinpoint missing "
                                + "required fields, invalid formats (email/date/uuid), enum violations, or "
                                + "type mismatches in the payload.");
            case 500:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.UNHANDLED_SERVER_CRASH,
                        "Endpoint returned HTTP 500 — an unhandled server-side runtime exception occurred.",
                        "Inspect backend logs for the reported stack trace. Look for null-pointer dereferences, "
                                + "unexpected database errors, or uncaught exceptions on this endpoint path.");
            case 504:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.GATEWAY_OR_BACKEND_TIMEOUT,
                        "Endpoint returned HTTP 504 Gateway/Backend timeout.",
                        "Investigate upstream gateway timeouts, slow database queries, or a downstream "
                                + "dependency that hangs beyond the gateway timeout envelope.");
            case 429:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.RATE_LIMIT_EXCEEDED,
                        "Endpoint returned HTTP 429 — the request was throttled by a rate limiter.",
                        "Honor the Retry-After header, reduce per-test concurrency, or spread requests. "
                                + "Tune the agent's concurrency after the traffic policy of the target.");
            default:
                return new DiagnosticFinding(step.getId(), step.getName(), method, path, status, outcome,
                        Category.UNKNOWN,
                        "Unclassified failure with HTTP " + status + ". Evidence: " + sanitize(reason),
                        "Review the captured response body/assertions for this step and reconcile with the "
                                + "expected status contract defined in the OpenAPI spec.");
        }
    }

    /**
     * For GET/DELETE/PATCH/PUT 404 steps, if the same entity's CREATE step previously failed,
     * surface that as the primary cause instead of a generic "missing resource".
     */
    private void correlateMissingResources(List<DiagnosticFinding> findings,
                                           List<String[]> failedCreateCandidates) {
        for (DiagnosticFinding finding : findings) {
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
            findings.set(findings.indexOf(finding),
                    new DiagnosticFinding(
                            finding.getStepId(), finding.getStepName(), finding.getMethod(),
                            finding.getPath(), finding.getResponseStatus(), finding.getOutcome(),
                            Category.RESOURCE_NOT_FOUND,
                            "HTTP 404 while reading/updating entity '" + entity + "'. The corresponding "
                                    + "CREATE step failed earlier in this run, so the resource may never "
                                    + "have been created.",
                            "Resolve the upstream CREATE failure first, then re-run so the resource is "
                                    + "created before dependent GET/DELETE operations.")
            );
        }
    }

    private String extractEntity(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int slash = trimmed.indexOf('/');
        if (slash > 0) {
            trimmed = trimmed.substring(0, slash);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private StepOutcome toOutcome(StepStatus status) {
        switch (status) {
            case BLOCKED: return StepOutcome.BLOCKED;
            case TIMEOUT: return StepOutcome.TIMEOUT;
            case NETWORK_ERROR: return StepOutcome.NETWORK_ERROR;
            default: return StepOutcome.FAILED;
        }
    }

    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\n\\r]+", " ").trim();
    }
}
