package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cleanup_records")
public class CleanupRecord {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 256)
    private String resourceId;

    @Column(name = "delete_endpoint", nullable = false, length = 512)
    private String deleteEndpoint;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder = 0;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, COMPLETED, FAILED, SKIPPED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "cleaned_at")
    private OffsetDateTime cleanedAt;

    public CleanupRecord() {}

    public CleanupRecord(String id, TestRun testRun, String resourceType, String resourceId, String deleteEndpoint, int executionOrder) {
        this.id = id;
        this.testRun = testRun;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.deleteEndpoint = deleteEndpoint;
        this.executionOrder = executionOrder;
        this.status = "PENDING";
        this.createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getDeleteEndpoint() { return deleteEndpoint; }
    public void setDeleteEndpoint(String deleteEndpoint) { this.deleteEndpoint = deleteEndpoint; }

    public int getExecutionOrder() { return executionOrder; }
    public void setExecutionOrder(int executionOrder) { this.executionOrder = executionOrder; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getCleanedAt() { return cleanedAt; }
    public void setCleanedAt(OffsetDateTime cleanedAt) { this.cleanedAt = cleanedAt; }
}
