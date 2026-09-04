package com.syed.apiqa.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Summary DTO for canonical run accounting, invariant verification, and root-cause intelligence.
 * Defines the single source of truth for dashboard, results, audit reports, and vector PDFs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RootCauseSummaryDto implements Serializable {

    private String runId;

    // --- 4 Canonical Pillars of Execution Truth ---
    private int discoveredOperations;      // Unique OpenAPI routes/operations discovered
    private int uniqueEndpointsDispatched; // Unique operations with at least 1 wire dispatch
    private int totalPlannedTests;         // Total test cases / steps generated in plan
    private int httpSentCount;             // Requests sent over the wire (HTTP_SENT = true)
    private int httpNotSentCount;          // Requests withheld / blocked (HTTP_SENT = false)

    // --- Wire Dispatched Outcomes (httpSentCount = passedCount + failedCount + otherTerminalCount) ---
    private int passedCount;
    private int failedCount;
    private int otherTerminalCount;

    // --- Non-Dispatched Outcomes (httpNotSentCount = blockedCount + unsupportedCount) ---
    private int blockedCount;
    private int unsupportedCount;

    // --- Accounting Invariant Verification ---
    private boolean isReconciled;
    private String accountingStatus; // "VALID" or "INVALID_ACCOUNTING_MISMATCH"
    private String reconciliationEquation;

    // --- Root Cause Groupings ---
    private List<RootCauseGroup> failureGroups = new ArrayList<>();
    private List<RootCauseGroup> blockedGroups = new ArrayList<>();

    public RootCauseSummaryDto() {}

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public int getDiscoveredOperations() { return discoveredOperations; }
    public void setDiscoveredOperations(int discoveredOperations) { this.discoveredOperations = discoveredOperations; }

    public int getUniqueEndpointsDispatched() { return uniqueEndpointsDispatched; }
    public void setUniqueEndpointsDispatched(int uniqueEndpointsDispatched) { this.uniqueEndpointsDispatched = uniqueEndpointsDispatched; }

    public int getTotalPlannedTests() { return totalPlannedTests; }
    public void setTotalPlannedTests(int totalPlannedTests) { this.totalPlannedTests = totalPlannedTests; }

    public int getHttpSentCount() { return httpSentCount; }
    public void setHttpSentCount(int httpSentCount) { this.httpSentCount = httpSentCount; }

    public int getHttpNotSentCount() { return httpNotSentCount; }
    public void setHttpNotSentCount(int httpNotSentCount) { this.httpNotSentCount = httpNotSentCount; }

    public int getPassedCount() { return passedCount; }
    public void setPassedCount(int passedCount) { this.passedCount = passedCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public int getOtherTerminalCount() { return otherTerminalCount; }
    public void setOtherTerminalCount(int otherTerminalCount) { this.otherTerminalCount = otherTerminalCount; }

    public int getBlockedCount() { return blockedCount; }
    public void setBlockedCount(int blockedCount) { this.blockedCount = blockedCount; }

    public int getUnsupportedCount() { return unsupportedCount; }
    public void setUnsupportedCount(int unsupportedCount) { this.unsupportedCount = unsupportedCount; }

    public boolean isReconciled() { return isReconciled; }
    public void setReconciled(boolean reconciled) { isReconciled = reconciled; }

    public String getAccountingStatus() { return accountingStatus; }
    public void setAccountingStatus(String accountingStatus) { this.accountingStatus = accountingStatus; }

    public String getReconciliationEquation() { return reconciliationEquation; }
    public void setReconciliationEquation(String reconciliationEquation) { this.reconciliationEquation = reconciliationEquation; }

    public List<RootCauseGroup> getFailureGroups() { return failureGroups; }
    public void setFailureGroups(List<RootCauseGroup> failureGroups) { this.failureGroups = failureGroups; }

    public List<RootCauseGroup> getBlockedGroups() { return blockedGroups; }
    public void setBlockedGroups(List<RootCauseGroup> blockedGroups) { this.blockedGroups = blockedGroups; }

    public static class RootCauseGroup implements Serializable {
        private String category;
        private String title;
        private String rootCause;
        private int affectedCount;
        private List<String> affectedStepIds = new ArrayList<>();
        private List<String> sampleOperations = new ArrayList<>();
        private String suggestedRemediation;

        public RootCauseGroup() {}

        public RootCauseGroup(String category, String title, String rootCause, int affectedCount, String suggestedRemediation) {
            this.category = category;
            this.title = title;
            this.rootCause = rootCause;
            this.affectedCount = affectedCount;
            this.suggestedRemediation = suggestedRemediation;
        }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getRootCause() { return rootCause; }
        public void setRootCause(String rootCause) { this.rootCause = rootCause; }

        public int getAffectedCount() { return affectedCount; }
        public void setAffectedCount(int affectedCount) { this.affectedCount = affectedCount; }

        public List<String> getAffectedStepIds() { return affectedStepIds; }
        public void setAffectedStepIds(List<String> affectedStepIds) { this.affectedStepIds = affectedStepIds; }

        public List<String> getSampleOperations() { return sampleOperations; }
        public void setSampleOperations(List<String> sampleOperations) { this.sampleOperations = sampleOperations; }

        public String getSuggestedRemediation() { return suggestedRemediation; }
        public void setSuggestedRemediation(String suggestedRemediation) { this.suggestedRemediation = suggestedRemediation; }
    }
}
