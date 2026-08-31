package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persisted entity recording deterministic testing coverage and behavior classification per endpoint.
 * Documented Reason: Provides verifiable evidence explaining why an endpoint achieved FULL, PARTIAL,
 * BLOCKED, or UNSUPPORTED coverage without AI ambiguity.
 */
@Entity
@Table(name = "endpoint_coverage")
public class EndpointCoverage {

    public enum Classification {
        FULL,
        PARTIAL,
        BLOCKED,
        UNSUPPORTED
    }

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 2048)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Classification classification;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "crud_tested")
    private boolean crudTested = false;

    @Column(name = "negative_tested")
    private boolean negativeTested = false;

    @Column(name = "contract_validated")
    private boolean contractValidated = false;

    @Column(name = "assertions_count")
    private int assertionsCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public EndpointCoverage() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = OffsetDateTime.now();
    }

    public EndpointCoverage(TestRun testRun, String method, String path,
                            Classification classification, String reason) {
        this();
        this.testRun = testRun;
        this.method = method;
        this.path = path;
        this.classification = classification;
        this.reason = reason;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Classification getClassification() { return classification; }
    public void setClassification(Classification classification) { this.classification = classification; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public boolean isCrudTested() { return crudTested; }
    public void setCrudTested(boolean crudTested) { this.crudTested = crudTested; }

    public boolean isNegativeTested() { return negativeTested; }
    public void setNegativeTested(boolean negativeTested) { this.negativeTested = negativeTested; }

    public boolean isContractValidated() { return contractValidated; }
    public void setContractValidated(boolean contractValidated) { this.contractValidated = contractValidated; }

    public int getAssertionsCount() { return assertionsCount; }
    public void setAssertionsCount(int assertionsCount) { this.assertionsCount = assertionsCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
