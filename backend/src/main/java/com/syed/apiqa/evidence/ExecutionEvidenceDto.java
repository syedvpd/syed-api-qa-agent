package com.syed.apiqa.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Canonical Execution Evidence Model.
 * Represents complete verifiable audit evidence for a single test step execution.
 * Guarantees zero ambiguity between HTTP_SENT=true network executions vs HTTP_SENT=false blocked checks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionEvidenceDto implements Serializable {

    // 1. IDENTITY
    private String runId;
    private String caseId;
    private String caseName;
    private String scenarioType;
    private String stepId;
    private int stepOrder;
    private String stepName;
    private String operationId;
    private String method;
    private String pathTemplate;

    // 2. REQUEST
    private String originalTemplate;
    private String resolvedUrl;
    private Map<String, String> pathParams;
    private Map<String, String> queryParams;
    private Map<String, String> requestHeaders;
    private String requestBody;
    private String requestContentType;
    private String requestGenerationSource;

    // 3. SECURITY
    private boolean securityRequired;
    private String securitySchemeType;
    private String selectedIdentity;
    private String authStrategy;
    private String authState;
    private boolean secretsRedacted = true;

    // 4. EXECUTION
    private boolean httpSent;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private Long latencyMs;
    private int retryCount;
    private String retryReason;

    // 5. RESPONSE
    private Integer responseStatus;
    private String responseStatusText;
    private Map<String, String> responseHeaders;
    private String responseBody;
    private String responseContentType;
    private Long responseSize;

    // 6. VALIDATION
    private boolean requestSchemaValid;
    private boolean responseSchemaValid;
    private Integer expectedStatus;
    private Integer actualStatus;
    private boolean statusPassed;
    private List<AssertionItemDto> assertions = Collections.emptyList();
    private List<String> validationFindings = Collections.emptyList();

    // 7. DEPENDENCIES
    private boolean hasDependency;
    private String producerStepId;
    private String producerMethodPath;
    private List<String> requiredVariables = Collections.emptyList();
    private Map<String, String> resolvedVariables = Collections.emptyMap();
    private String dependencyStatus;
    private String upstreamFailureReason;

    // 8. FINAL RESULT
    private String status; // PASSED, FAILED, BLOCKED, UNSUPPORTED, REQUEST_NOT_EXECUTABLE

    // 9. DIAGNOSIS
    private String classification;
    private String rootCause;
    private String customerExplanation;
    private String suggestedRemediation;

    public ExecutionEvidenceDto() {}

    // Getters and Setters
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getCaseName() { return caseName; }
    public void setCaseName(String caseName) { this.caseName = caseName; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }

    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPathTemplate() { return pathTemplate; }
    public void setPathTemplate(String pathTemplate) { this.pathTemplate = pathTemplate; }

    public String getOriginalTemplate() { return originalTemplate; }
    public void setOriginalTemplate(String originalTemplate) { this.originalTemplate = originalTemplate; }

    public String getResolvedUrl() { return resolvedUrl; }
    public void setResolvedUrl(String resolvedUrl) { this.resolvedUrl = resolvedUrl; }

    public Map<String, String> getPathParams() { return pathParams; }
    public void setPathParams(Map<String, String> pathParams) { this.pathParams = pathParams; }

    public Map<String, String> getQueryParams() { return queryParams; }
    public void setQueryParams(Map<String, String> queryParams) { this.queryParams = queryParams; }

    public Map<String, String> getRequestHeaders() { return requestHeaders; }
    public void setRequestHeaders(Map<String, String> requestHeaders) { this.requestHeaders = requestHeaders; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public String getRequestContentType() { return requestContentType; }
    public void setRequestContentType(String requestContentType) { this.requestContentType = requestContentType; }

    public String getRequestGenerationSource() { return requestGenerationSource; }
    public void setRequestGenerationSource(String requestGenerationSource) { this.requestGenerationSource = requestGenerationSource; }

    public boolean isSecurityRequired() { return securityRequired; }
    public void setSecurityRequired(boolean securityRequired) { this.securityRequired = securityRequired; }

    public String getSecuritySchemeType() { return securitySchemeType; }
    public void setSecuritySchemeType(String securitySchemeType) { this.securitySchemeType = securitySchemeType; }

    public String getSelectedIdentity() { return selectedIdentity; }
    public void setSelectedIdentity(String selectedIdentity) { this.selectedIdentity = selectedIdentity; }

    public String getAuthStrategy() { return authStrategy; }
    public void setAuthStrategy(String authStrategy) { this.authStrategy = authStrategy; }

    public String getAuthState() { return authState; }
    public void setAuthState(String authState) { this.authState = authState; }

    public boolean isSecretsRedacted() { return secretsRedacted; }
    public void setSecretsRedacted(boolean secretsRedacted) { this.secretsRedacted = secretsRedacted; }

    public boolean isHttpSent() { return httpSent; }
    public void setHttpSent(boolean httpSent) { this.httpSent = httpSent; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }

    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }

    public String getResponseStatusText() { return responseStatusText; }
    public void setResponseStatusText(String responseStatusText) { this.responseStatusText = responseStatusText; }

    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public String getResponseContentType() { return responseContentType; }
    public void setResponseContentType(String responseContentType) { this.responseContentType = responseContentType; }

    public Long getResponseSize() { return responseSize; }
    public void setResponseSize(Long responseSize) { this.responseSize = responseSize; }

    public boolean isRequestSchemaValid() { return requestSchemaValid; }
    public void setRequestSchemaValid(boolean requestSchemaValid) { this.requestSchemaValid = requestSchemaValid; }

    public boolean isResponseSchemaValid() { return responseSchemaValid; }
    public void setResponseSchemaValid(boolean responseSchemaValid) { this.responseSchemaValid = responseSchemaValid; }

    public Integer getExpectedStatus() { return expectedStatus; }
    public void setExpectedStatus(Integer expectedStatus) { this.expectedStatus = expectedStatus; }

    public Integer getActualStatus() { return actualStatus; }
    public void setActualStatus(Integer actualStatus) { this.actualStatus = actualStatus; }

    public boolean isStatusPassed() { return statusPassed; }
    public void setStatusPassed(boolean statusPassed) { this.statusPassed = statusPassed; }

    public List<AssertionItemDto> getAssertions() { return assertions; }
    public void setAssertions(List<AssertionItemDto> assertions) { this.assertions = assertions != null ? assertions : Collections.emptyList(); }

    public List<String> getValidationFindings() { return validationFindings; }
    public void setValidationFindings(List<String> validationFindings) { this.validationFindings = validationFindings != null ? validationFindings : Collections.emptyList(); }

    public boolean isHasDependency() { return hasDependency; }
    public void setHasDependency(boolean hasDependency) { this.hasDependency = hasDependency; }

    public String getProducerStepId() { return producerStepId; }
    public void setProducerStepId(String producerStepId) { this.producerStepId = producerStepId; }

    public String getProducerMethodPath() { return producerMethodPath; }
    public void setProducerMethodPath(String producerMethodPath) { this.producerMethodPath = producerMethodPath; }

    public List<String> getRequiredVariables() { return requiredVariables; }
    public void setRequiredVariables(List<String> requiredVariables) { this.requiredVariables = requiredVariables != null ? requiredVariables : Collections.emptyList(); }

    public Map<String, String> getResolvedVariables() { return resolvedVariables; }
    public void setResolvedVariables(Map<String, String> resolvedVariables) { this.resolvedVariables = resolvedVariables != null ? resolvedVariables : Collections.emptyMap(); }

    public String getDependencyStatus() { return dependencyStatus; }
    public void setDependencyStatus(String dependencyStatus) { this.dependencyStatus = dependencyStatus; }

    public String getUpstreamFailureReason() { return upstreamFailureReason; }
    public void setUpstreamFailureReason(String upstreamFailureReason) { this.upstreamFailureReason = upstreamFailureReason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getCustomerExplanation() { return customerExplanation; }
    public void setCustomerExplanation(String customerExplanation) { this.customerExplanation = customerExplanation; }

    public String getSuggestedRemediation() { return suggestedRemediation; }
    public void setSuggestedRemediation(String suggestedRemediation) { this.suggestedRemediation = suggestedRemediation; }

    public static class AssertionItemDto implements Serializable {
        private String assertionType;
        private String targetField;
        private String expectedValue;
        private String actualValue;
        private boolean passed;
        private String message;

        public AssertionItemDto() {}

        public AssertionItemDto(String assertionType, String targetField, String expectedValue, String actualValue, boolean passed, String message) {
            this.assertionType = assertionType;
            this.targetField = targetField;
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
            this.passed = passed;
            this.message = message;
        }

        public String getAssertionType() { return assertionType; }
        public void setAssertionType(String assertionType) { this.assertionType = assertionType; }

        public String getTargetField() { return targetField; }
        public void setTargetField(String targetField) { this.targetField = targetField; }

        public String getExpectedValue() { return expectedValue; }
        public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }

        public String getActualValue() { return actualValue; }
        public void setActualValue(String actualValue) { this.actualValue = actualValue; }

        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
