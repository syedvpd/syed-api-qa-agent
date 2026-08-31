package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persisted entity capturing an individual regression delta between two test runs.
 * Documented Reason: Enables granular historical tracking of new failures, fixed failures,
 * added/removed endpoints, status shifts, and latency regressions.
 */
@Entity
@Table(name = "regression_findings")
public class RegressionFinding {

    public enum FindingType {
        NEW_FAILURE,
        FIXED_FAILURE,
        API_ADDED,
        API_REMOVED,
        STATUS_CHANGED,
        LATENCY_REGRESSION,
        CONTRACT_DRIFT
    }

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "baseline_run_id")
    private TestRun baselineRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", length = 64, nullable = false)
    private FindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Severity severity;

    @Column(name = "endpoint_path", length = 512)
    private String endpointPath;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Column(name = "baseline_value", length = 256)
    private String baselineValue;

    @Column(name = "current_value", length = 256)
    private String currentValue;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public RegressionFinding() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = OffsetDateTime.now();
    }

    public RegressionFinding(TestRun testRun, TestRun baselineRun, FindingType findingType,
                             Severity severity, String httpMethod, String endpointPath,
                             String baselineValue, String currentValue, String description) {
        this();
        this.testRun = testRun;
        this.baselineRun = baselineRun;
        this.findingType = findingType;
        this.severity = severity;
        this.httpMethod = httpMethod;
        this.endpointPath = endpointPath;
        this.baselineValue = baselineValue;
        this.currentValue = currentValue;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public TestRun getBaselineRun() { return baselineRun; }
    public void setBaselineRun(TestRun baselineRun) { this.baselineRun = baselineRun; }

    public FindingType getFindingType() { return findingType; }
    public void setFindingType(FindingType findingType) { this.findingType = findingType; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getBaselineValue() { return baselineValue; }
    public void setBaselineValue(String baselineValue) { this.baselineValue = baselineValue; }

    public String getCurrentValue() { return currentValue; }
    public void setCurrentValue(String currentValue) { this.currentValue = currentValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
