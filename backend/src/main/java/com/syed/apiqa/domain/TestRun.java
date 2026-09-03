package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents a complete autonomous test execution cycle against a deployed OpenAPI target.
 * Documented Reason: Tracks overarching lifecycle state, progress counters, execution duration, and results independently of browser connections.
 */
@Entity
@Table(name = "test_runs")
public class TestRun {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id")
    private Environment environment;

    @Column(name = "openapi_url", nullable = false, length = 1024)
    private String openapiUrl;

    @Column(name = "target_base_url", length = 1024)
    private String targetBaseUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RunStatus status = RunStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_type", nullable = false, length = 50)
    private EnvironmentType environmentType = EnvironmentType.STAGING;

    @Column(name = "total_endpoints", nullable = false)
    private int totalEndpoints = 0;

    @Column(name = "total_tests", nullable = false)
    private int totalTests = 0;

    @Column(name = "passed_tests", nullable = false)
    private int passedTests = 0;

    @Column(name = "failed_tests", nullable = false)
    private int failedTests = 0;

    @Column(name = "warning_tests", nullable = false)
    private int warningTests = 0;

    @Column(name = "blocked_tests", nullable = false)
    private int blockedTests = 0;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "auth_login_url", length = 512)
    private String authLoginUrl;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = com.syed.apiqa.safety.EncryptedStringConverter.class)
    @Column(name = "auth_login_payload", columnDefinition = "TEXT")
    private String authLoginPayload;

    @Column(name = "auth_token_path", length = 128)
    private String authTokenPath;

    @Column(name = "auth_refresh_url", length = 512)
    private String authRefreshUrl;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = com.syed.apiqa.safety.EncryptedStringConverter.class)
    @Column(name = "credential_profiles_json", columnDefinition = "TEXT")
    private String credentialProfilesJson;

    @Column(name = "cleanup_status", length = 32)
    private String cleanupStatus = "NOT_RUN";

    @Column(name = "baseline_run_id", length = 36)
    private String baselineRunId;

    @Column(name = "regression_summary_json", columnDefinition = "TEXT")
    private String regressionSummaryJson;

    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @Column(name = "cancellation_reason", length = 512)
    private String cancellationReason;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 600;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "coverage_score")
    private Double coverageScore;

    @Column(name = "coverage_summary_json", columnDefinition = "TEXT")
    private String coverageSummaryJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public TestRun() {}

    public TestRun(String id, String openapiUrl, EnvironmentType environmentType) {
        this.id = id;
        this.openapiUrl = openapiUrl;
        this.environmentType = environmentType;
        this.status = RunStatus.CREATED;
        this.createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }

    public String getOpenapiUrl() { return openapiUrl; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }

    public String getTargetBaseUrl() { return targetBaseUrl; }
    public void setTargetBaseUrl(String targetBaseUrl) { this.targetBaseUrl = targetBaseUrl; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public EnvironmentType getEnvironmentType() { return environmentType; }
    public void setEnvironmentType(EnvironmentType environmentType) { this.environmentType = environmentType; }

    public int getTotalEndpoints() { return totalEndpoints; }
    public void setTotalEndpoints(int totalEndpoints) { this.totalEndpoints = totalEndpoints; }

    public int getTotalTests() { return totalTests; }
    public void setTotalTests(int totalTests) { this.totalTests = totalTests; }

    public int getPassedTests() { return passedTests; }
    public void setPassedTests(int passedTests) { this.passedTests = passedTests; }

    public int getFailedTests() { return failedTests; }
    public void setFailedTests(int failedTests) { this.failedTests = failedTests; }

    public int getWarningTests() { return warningTests; }
    public void setWarningTests(int warningTests) { this.warningTests = warningTests; }

    public int getBlockedTests() { return blockedTests; }
    public void setBlockedTests(int blockedTests) { this.blockedTests = blockedTests; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getAuthLoginUrl() { return authLoginUrl; }
    public void setAuthLoginUrl(String authLoginUrl) { this.authLoginUrl = authLoginUrl; }

    public String getAuthLoginPayload() { return authLoginPayload; }
    public void setAuthLoginPayload(String authLoginPayload) { this.authLoginPayload = authLoginPayload; }

    public String getAuthTokenPath() { return authTokenPath; }
    public void setAuthTokenPath(String authTokenPath) { this.authTokenPath = authTokenPath; }

    public String getAuthRefreshUrl() { return authRefreshUrl; }
    public void setAuthRefreshUrl(String authRefreshUrl) { this.authRefreshUrl = authRefreshUrl; }

    public String getCleanupStatus() { return cleanupStatus; }
    public void setCleanupStatus(String cleanupStatus) { this.cleanupStatus = cleanupStatus; }

    public String getBaselineRunId() { return baselineRunId; }
    public void setBaselineRunId(String baselineRunId) { this.baselineRunId = baselineRunId; }

    public String getRegressionSummaryJson() { return regressionSummaryJson; }
    public void setRegressionSummaryJson(String regressionSummaryJson) { this.regressionSummaryJson = regressionSummaryJson; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }

    public Integer getTimeoutSeconds() { return timeoutSeconds != null ? timeoutSeconds : 600; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Double getCoverageScore() { return coverageScore; }
    public void setCoverageScore(Double coverageScore) { this.coverageScore = coverageScore; }

    public String getCoverageSummaryJson() { return coverageSummaryJson; }
    public void setCoverageSummaryJson(String coverageSummaryJson) { this.coverageSummaryJson = coverageSummaryJson; }

    public String getCredentialProfilesJson() { return credentialProfilesJson; }
    public void setCredentialProfilesJson(String credentialProfilesJson) { this.credentialProfilesJson = credentialProfilesJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
