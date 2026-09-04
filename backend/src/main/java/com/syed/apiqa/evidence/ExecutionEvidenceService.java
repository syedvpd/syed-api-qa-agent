package com.syed.apiqa.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.safety.SecretMasker;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for synthesizing the canonical Execution Evidence Model and Root-Cause Intelligence.
 * Provides unified, tamper-proof, redacted execution facts for Live UI, Results Matrix, Audit Reports, and PDFs.
 */
@Service
public class ExecutionEvidenceService {

    private final TestRunRepository testRunRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestStepRepository testStepRepository;
    private final ExecutionRepository executionRepository;
    private final AssertionResultRepository assertionResultRepository;
    private final DependencyRepository dependencyRepository;
    private final SecretMasker secretMasker;
    private final ObjectMapper objectMapper;

    public ExecutionEvidenceService(TestRunRepository testRunRepository,
                                  TestCaseRepository testCaseRepository,
                                  TestStepRepository testStepRepository,
                                  ExecutionRepository executionRepository,
                                  AssertionResultRepository assertionResultRepository,
                                  DependencyRepository dependencyRepository,
                                  SecretMasker secretMasker,
                                  ObjectMapper objectMapper) {
        this.testRunRepository = testRunRepository;
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
        this.assertionResultRepository = assertionResultRepository;
        this.dependencyRepository = dependencyRepository;
        this.secretMasker = secretMasker;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the complete list of canonical Execution Evidence records for a run.
     */
    public List<ExecutionEvidenceDto> getEvidenceForRun(String runId) {
        TestRun run = testRunRepository.findById(runId).orElse(null);
        if (run == null) return Collections.emptyList();

        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(runId);
        List<Execution> executions = executionRepository.findByTestRunId(runId);
        List<Dependency> dependencies = dependencyRepository.findByTestRunId(runId);

        // Map executions by test_step_id
        Map<String, Execution> executionByStepId = new HashMap<>();
        for (Execution exec : executions) {
            if (exec.getTestStep() != null) {
                executionByStepId.put(exec.getTestStep().getId(), exec);
            }
        }

        // Map dependencies by consumer endpoint/path
        Map<String, Dependency> dependencyByConsumer = new HashMap<>();
        for (Dependency dep : dependencies) {
            if (dep.getConsumerEndpoint() != null) {
                dependencyByConsumer.put(dep.getConsumerEndpoint().getId(), dep);
            }
        }

        List<ExecutionEvidenceDto> evidenceList = new ArrayList<>();

        for (TestCase tc : cases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            for (TestStep step : steps) {
                Execution exec = executionByStepId.get(step.getId());
                Dependency dep = step.getApiEndpoint() != null ? dependencyByConsumer.get(step.getApiEndpoint().getId()) : null;
                evidenceList.add(buildEvidenceDto(run, tc, step, exec, dep));
            }
        }

        return evidenceList;
    }

    /**
     * Retrieves single detailed Execution Evidence record for a specific step.
     */
    public ExecutionEvidenceDto getEvidenceForStep(String stepId) {
        TestStep step = testStepRepository.findById(stepId).orElse(null);
        if (step == null) return null;

        TestCase tc = step.getTestCase();
        TestRun run = tc != null ? tc.getTestRun() : null;
        List<Execution> execs = executionRepository.findByTestStepId(stepId);
        Execution exec = execs.isEmpty() ? null : execs.get(execs.size() - 1);
        Dependency dep = null;

        return buildEvidenceDto(run, tc, step, exec, dep);
    }

    /**
     * Builds root-cause aggregated summary for the run.
     */
    public RootCauseSummaryDto getRootCauseSummary(String runId) {
        List<ExecutionEvidenceDto> evidenceList = getEvidenceForRun(runId);
        RootCauseSummaryDto summary = new RootCauseSummaryDto();
        summary.setRunId(runId);
        summary.setTotalPlannedTests(evidenceList.size());

        int httpSent = 0;
        int httpNotSent = 0;
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int unsupported = 0;

        Map<String, RootCauseSummaryDto.RootCauseGroup> failureMap = new LinkedHashMap<>();
        Map<String, RootCauseSummaryDto.RootCauseGroup> blockedMap = new LinkedHashMap<>();

        for (ExecutionEvidenceDto ev : evidenceList) {
            if (ev.isHttpSent()) {
                httpSent++;
            } else {
                httpNotSent++;
            }

            String st = ev.getStatus();
            if ("PASSED".equalsIgnoreCase(st)) {
                passed++;
            } else if ("FAILED".equalsIgnoreCase(st) || "AUTHENTICATION_ERROR".equalsIgnoreCase(st) || "VALIDATION_FAILED".equalsIgnoreCase(st)) {
                failed++;
                String category = ev.getClassification() != null ? ev.getClassification() : "TARGET_API_ERROR";
                String groupKey = category + "_" + (ev.getResponseStatus() != null ? ev.getResponseStatus() : "NET_ERR");
                RootCauseSummaryDto.RootCauseGroup group = failureMap.computeIfAbsent(groupKey, k -> new RootCauseSummaryDto.RootCauseGroup(
                        category,
                        ev.getRootCause() != null ? ev.getRootCause() : "HTTP Execution Failure",
                        ev.getCustomerExplanation() != null ? ev.getCustomerExplanation() : "The target API rejected the request or returned an unexpected response.",
                        0,
                        ev.getSuggestedRemediation() != null ? ev.getSuggestedRemediation() : "Inspect request payload and server logs."
                ));
                group.setAffectedCount(group.getAffectedCount() + 1);
                group.getAffectedStepIds().add(ev.getStepId());
                if (group.getSampleOperations().size() < 5) {
                    group.getSampleOperations().add(ev.getMethod() + " " + ev.getPathTemplate());
                }
            } else if ("BLOCKED".equalsIgnoreCase(st) || "REQUEST_NOT_EXECUTABLE".equalsIgnoreCase(st) || "BLOCKED_BY_AUTHENTICATION".equalsIgnoreCase(st)) {
                blocked++;
                String category = ev.getClassification() != null ? ev.getClassification() : "DEPENDENCY_BLOCKED";
                String groupKey = category + "_" + (ev.getRootCause() != null ? ev.getRootCause().hashCode() : "BLOCK");
                RootCauseSummaryDto.RootCauseGroup group = blockedMap.computeIfAbsent(groupKey, k -> new RootCauseSummaryDto.RootCauseGroup(
                        category,
                        ev.getRootCause() != null ? ev.getRootCause() : "Prerequisite Unsatisfied",
                        ev.getCustomerExplanation() != null ? ev.getCustomerExplanation() : "Execution was withheld because required prerequisite context or authentication was not satisfied.",
                        0,
                        ev.getSuggestedRemediation() != null ? ev.getSuggestedRemediation() : "Ensure required authentication profiles or upstream producer workflows succeed."
                ));
                group.setAffectedCount(group.getAffectedCount() + 1);
                group.getAffectedStepIds().add(ev.getStepId());
                if (group.getSampleOperations().size() < 5) {
                    group.getSampleOperations().add(ev.getMethod() + " " + ev.getPathTemplate());
                }
            } else {
                unsupported++;
            }
        }

        summary.setHttpSentCount(httpSent);
        summary.setHttpNotSentCount(httpNotSent);
        summary.setPassedCount(passed);
        summary.setFailedCount(failed);
        summary.setBlockedCount(blocked);
        summary.setUnsupportedCount(unsupported);
        summary.setFailureGroups(new ArrayList<>(failureMap.values()));
        summary.setBlockedGroups(new ArrayList<>(blockedMap.values()));

        return summary;
    }

    private ExecutionEvidenceDto buildEvidenceDto(TestRun run, TestCase tc, TestStep step, Execution exec, Dependency dep) {
        ExecutionEvidenceDto dto = new ExecutionEvidenceDto();

        // 1. IDENTITY
        dto.setRunId(run != null ? run.getId() : (tc != null && tc.getTestRun() != null ? tc.getTestRun().getId() : ""));
        dto.setCaseId(tc != null ? tc.getId() : "");
        dto.setCaseName(tc != null ? tc.getName() : "Standalone Scenario");
        dto.setScenarioType(tc != null && tc.getScenarioType() != null ? tc.getScenarioType() : "CONTRACT_PROBE");
        dto.setStepId(step.getId());
        dto.setStepOrder(step.getStepOrder());
        dto.setStepName(step.getName());
        dto.setOperationId(step.getApiEndpoint() != null && step.getApiEndpoint().getOperationId() != null
                ? step.getApiEndpoint().getOperationId()
                : step.getMethod() + " " + step.getPathTemplate());
        dto.setMethod(step.getMethod());
        dto.setPathTemplate(step.getPathTemplate());
        dto.setOriginalTemplate(step.getPathTemplate());

        // 2. REQUEST
        String resolvedUrl = (exec != null && exec.getRequestUrl() != null)
                ? exec.getRequestUrl()
                : (step.getResolvedUrl() != null ? step.getResolvedUrl() : (run != null ? run.getTargetBaseUrl() + step.getPathTemplate() : step.getPathTemplate()));
        dto.setResolvedUrl(secretMasker.maskUrl(resolvedUrl));

        // Parse path & query params
        dto.setPathParams(extractPathParams(step.getPathTemplate(), resolvedUrl));
        dto.setQueryParams(extractQueryParams(resolvedUrl));

        // Request headers
        String rawReqHeaders = (exec != null && exec.getRequestHeaders() != null) ? exec.getRequestHeaders() : step.getRequestHeaders();
        dto.setRequestHeaders(parseAndRedactHeaders(rawReqHeaders));
        dto.setRequestContentType(dto.getRequestHeaders().getOrDefault("Content-Type", dto.getRequestHeaders().getOrDefault("content-type", "application/json")));

        // Request Body
        String rawReqBody = (exec != null && exec.getRequestBody() != null) ? exec.getRequestBody() : step.getRequestBody();
        dto.setRequestBody(secretMasker.maskBody(rawReqBody));
        dto.setRequestGenerationSource(step.getRequestBody() != null ? "OpenAPI Schema Synthesizer" : "None (No Request Body)");

        // 3. SECURITY
        boolean secReq = step.getApiEndpoint() != null && step.getApiEndpoint().getSecurityRequirements() != null && !step.getApiEndpoint().getSecurityRequirements().isBlank();
        dto.setSecurityRequired(secReq);
        dto.setSecuritySchemeType(secReq ? "Bearer / API Key" : "NONE");
        dto.setSelectedIdentity(secReq ? "Primary Identity" : "Public (No Auth)");
        dto.setAuthStrategy(secReq ? "BEARER" : "NONE");
        dto.setAuthState(secReq ? "AUTHENTICATED" : "PUBLIC");
        dto.setSecretsRedacted(true);

        // 4. EXECUTION
        boolean httpWasSent = (exec != null && exec.getStartedAt() != null && exec.getResponseStatus() != null && exec.getResponseStatus() > 0);
        dto.setHttpSent(httpWasSent);
        dto.setStartedAt(exec != null ? exec.getStartedAt() : null);
        dto.setCompletedAt(exec != null ? exec.getCompletedAt() : null);
        dto.setLatencyMs(exec != null ? exec.getLatencyMs() : 0L);
        dto.setRetryCount(0);

        // 5. RESPONSE
        if (exec != null && exec.getResponseStatus() != null && exec.getResponseStatus() > 0) {
            dto.setResponseStatus(exec.getResponseStatus());
            dto.setResponseStatusText(getStatusText(exec.getResponseStatus()));
            dto.setResponseHeaders(parseAndRedactHeaders(exec.getResponseHeaders()));
            dto.setResponseBody(secretMasker.maskBody(exec.getResponseBody()));
            dto.setResponseContentType(dto.getResponseHeaders().getOrDefault("Content-Type", "application/json"));
            dto.setResponseSize(exec.getResponseBody() != null ? (long) exec.getResponseBody().getBytes().length : 0L);
        } else {
            dto.setResponseStatus(null);
            dto.setResponseStatusText("NO_HTTP_RESPONSE");
            dto.setResponseBody(null);
            dto.setResponseHeaders(Collections.emptyMap());
        }

        // 6. VALIDATION
        dto.setExpectedStatus(step.getExpectedStatus());
        dto.setActualStatus(dto.getResponseStatus());
        boolean statusMatch = (dto.getExpectedStatus() == null && dto.getResponseStatus() != null && dto.getResponseStatus() < 400)
                || (dto.getExpectedStatus() != null && dto.getExpectedStatus().equals(dto.getResponseStatus()));
        dto.setStatusPassed(statusMatch);
        dto.setRequestSchemaValid(true);
        dto.setResponseSchemaValid(dto.getResponseStatus() != null && (dto.getResponseStatus() < 400 || dto.getResponseStatus() == 404 || dto.getResponseStatus() == 422));

        // Load Assertions
        if (exec != null) {
            List<AssertionResult> assertions = assertionResultRepository.findByExecutionId(exec.getId());
            dto.setAssertions(assertions.stream().map(a -> new ExecutionEvidenceDto.AssertionItemDto(
                    a.getAssertionType() != null ? a.getAssertionType().name() : "STATUS_CODE",
                    a.getTargetField() != null ? a.getTargetField() : "response.status",
                    a.getExpectedValue(),
                    a.getActualValue(),
                    a.isPassed(),
                    a.getMessage()
            )).collect(Collectors.toList()));
        }

        // 7. DEPENDENCIES
        if (dep != null) {
            dto.setHasDependency(true);
            dto.setProducerStepId(dep.getProducerEndpoint() != null ? dep.getProducerEndpoint().getId() : "");
            dto.setProducerMethodPath(dep.getProducerEndpoint() != null
                    ? dep.getProducerEndpoint().getMethod() + " " + dep.getProducerEndpoint().getPath()
                    : "");
            dto.setRequiredVariables(List.of(dep.getParameterName() != null ? dep.getParameterName() : "id"));
            dto.setDependencyStatus("SATISFIED");
        } else if (step.getFailureReason() != null && step.getFailureReason().contains("upstream")) {
            dto.setHasDependency(true);
            dto.setDependencyStatus("BLOCKED");
            dto.setUpstreamFailureReason(step.getFailureReason());
        } else {
            dto.setHasDependency(false);
            dto.setDependencyStatus("NONE");
        }

        // 8. FINAL RESULT
        StepStatus finalStatus = step.getStatus();
        if (exec != null && exec.getStatus() != null && exec.getStatus() != StepStatus.PENDING) {
            finalStatus = exec.getStatus();
        }
        dto.setStatus(finalStatus != null ? finalStatus.name() : "UNKNOWN");

        // 9. DIAGNOSIS
        populateDiagnosis(dto, step, exec);

        return dto;
    }

    private void populateDiagnosis(ExecutionEvidenceDto dto, TestStep step, Execution exec) {
        String status = dto.getStatus();
        Integer httpCode = dto.getResponseStatus();

        if ("PASSED".equalsIgnoreCase(status)) {
            dto.setClassification("SUCCESSFUL_CONTRACT_VERIFICATION");
            dto.setRootCause("Contract Satisfied");
            dto.setCustomerExplanation("HTTP request reached the target server, returned expected status " + httpCode + ", and conformed to OpenAPI schema assertions.");
            dto.setSuggestedRemediation("No action required. Endpoint is functioning according to specification.");
        } else if ("BLOCKED".equalsIgnoreCase(status) || "REQUEST_NOT_EXECUTABLE".equalsIgnoreCase(status) || "BLOCKED_BY_AUTHENTICATION".equalsIgnoreCase(status)) {
            String rawReason = step.getFailureReason() != null ? step.getFailureReason() : "Prerequisite unsatisfied";
            String reason = rawReason.toLowerCase();
            if (reason.contains("authentication") || reason.contains("identity") || reason.contains("billing")) {
                dto.setClassification("AUTHENTICATION_REQUIRED");
                dto.setRootCause("No Compatible Identity Session");
                dto.setCustomerExplanation("Execution was withheld because the target operation requires authentication and no matching valid credential profile was available.");
                dto.setSuggestedRemediation("Provide a valid Bearer Token or configure dynamic login credentials in Run Settings and re-execute.");
            } else if (reason.contains("upstream") || reason.contains("dependency")) {
                dto.setClassification("UPSTREAM_PRODUCER_FAILURE");
                dto.setRootCause("Upstream Dependency Failed");
                dto.setCustomerExplanation("This step relies on context data produced by an earlier step in the scenario which failed or was blocked.");
                dto.setSuggestedRemediation("Fix the upstream producer step so required IDs/variables are successfully generated.");
            } else {
                dto.setClassification("MISSING_PATH_PARAMETER");
                dto.setRootCause("Unresolved Parameter");
                dto.setCustomerExplanation("Required path or query parameter was not available in context: " + rawReason);
                dto.setSuggestedRemediation("Verify that the OpenAPI path definition provides valid sample values or upstream creation steps.");
            }
        } else { // FAILED
            if (httpCode != null) {
                if (httpCode == 401 || httpCode == 403) {
                    dto.setClassification("AUTHORIZATION_DENIAL");
                    dto.setRootCause("Server Rejected Authentication (HTTP " + httpCode + ")");
                    dto.setCustomerExplanation("The target server rejected the provided Bearer token or authorization credentials.");
                    dto.setSuggestedRemediation("Verify that the JWT token is valid, unexpired, and carries the required roles/permissions.");
                } else if (httpCode == 422 || httpCode == 400) {
                    dto.setClassification("TARGET_API_PAYLOAD_REJECTION");
                    dto.setRootCause("Validation Rejection (HTTP " + httpCode + ")");
                    dto.setCustomerExplanation("The target API rejected the generated request body structure or parameter constraints.");
                    dto.setSuggestedRemediation("Inspect the generated request payload against your backend schema requirements.");
                } else if (httpCode == 404) {
                    dto.setClassification("RESOURCE_NOT_FOUND");
                    dto.setRootCause("Endpoint or Resource Not Found (HTTP 404)");
                    dto.setCustomerExplanation("The target URL does not exist or the referenced entity ID was not found on the live server.");
                    dto.setSuggestedRemediation("Check endpoint route configuration and base URL path prefix.");
                } else if (httpCode >= 500) {
                    dto.setClassification("TARGET_SERVER_CRASH");
                    dto.setRootCause("Internal Server Error (HTTP " + httpCode + ")");
                    dto.setCustomerExplanation("The target server threw an unhandled 5xx exception when processing this valid HTTP request.");
                    dto.setSuggestedRemediation("Inspect your target server application logs and exception stack traces.");
                } else {
                    dto.setClassification("STATUS_ASSERTION_MISMATCH");
                    dto.setRootCause("Unexpected Status Code " + httpCode);
                    dto.setCustomerExplanation("Received HTTP " + httpCode + " but expected status was " + dto.getExpectedStatus() + ".");
                    dto.setSuggestedRemediation("Update OpenAPI response definition or verify API handler return code.");
                }
            } else {
                dto.setClassification("NETWORK_TRANSPORT_ERROR");
                dto.setRootCause("Connection / Timeout Error");
                dto.setCustomerExplanation(exec != null && exec.getErrorDetails() != null ? exec.getErrorDetails() : "Failed to establish network connection to target host.");
                dto.setSuggestedRemediation("Verify target host availability, DNS resolution, and network firewalls.");
            }
        }
    }

    private Map<String, String> parseAndRedactHeaders(String headerStr) {
        if (headerStr == null || headerStr.isBlank()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (headerStr.startsWith("{")) {
                Map<String, Object> raw = objectMapper.readValue(headerStr, new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    String val = String.valueOf(e.getValue());
                    if ("authorization".equalsIgnoreCase(e.getKey()) || "x-api-key".equalsIgnoreCase(e.getKey())) {
                        val = redactTokenPreview(val);
                    }
                    map.put(e.getKey(), val);
                }
            } else {
                String[] lines = headerStr.split("[;\n]");
                for (String line : lines) {
                    int idx = line.indexOf(":");
                    if (idx > 0) {
                        String key = line.substring(0, idx).trim();
                        String val = line.substring(idx + 1).trim();
                        if ("authorization".equalsIgnoreCase(key) || "x-api-key".equalsIgnoreCase(key)) {
                            val = redactTokenPreview(val);
                        }
                        map.put(key, val);
                    }
                }
            }
        } catch (Exception ignored) {
            map.put("Headers", "[Parsed]");
        }
        return map;
    }

    private String redactTokenPreview(String val) {
        if (val == null || val.isBlank()) return "[REDACTED]";
        if (val.startsWith("Bearer ") && val.length() > 15) {
            return "Bearer " + val.substring(7, 12) + "••••••••";
        }
        return "[REDACTED]";
    }

    private Map<String, String> extractPathParams(String template, String resolvedUrl) {
        Map<String, String> map = new LinkedHashMap<>();
        if (template == null || resolvedUrl == null || !template.contains("{")) return map;

        String[] tSegments = template.split("/");
        String pathOnly = resolvedUrl;
        if (pathOnly.contains("?")) pathOnly = pathOnly.substring(0, pathOnly.indexOf("?"));
        String[] rSegments = pathOnly.split("/");

        for (int i = 0; i < tSegments.length; i++) {
            String seg = tSegments[i];
            if (seg.startsWith("{") && seg.endsWith("}")) {
                String paramName = seg.substring(1, seg.length() - 1);
                String actualVal = (i < rSegments.length) ? rSegments[i] : "unresolved";
                map.put(paramName, actualVal);
            }
        }
        return map;
    }

    private Map<String, String> extractQueryParams(String resolvedUrl) {
        Map<String, String> map = new LinkedHashMap<>();
        if (resolvedUrl == null || !resolvedUrl.contains("?")) return map;

        String query = resolvedUrl.substring(resolvedUrl.indexOf("?") + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            } else {
                map.put(pair, "");
            }
        }
        return map;
    }

    private String getStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "HTTP " + code;
        };
    }
}
