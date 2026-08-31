package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents a parent-child parameter dependency between two API operations.
 * Documented Reason: Enables deterministic DAG formulation, variable flow, and prevents execution deadlock without an LLM.
 */
@Entity
@Table(name = "dependencies")
public class Dependency {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "producer_endpoint_id", nullable = false)
    private ApiEndpoint producerEndpoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consumer_endpoint_id", nullable = false)
    private ApiEndpoint consumerEndpoint;

    @Column(name = "parameter_name", nullable = false)
    private String parameterName;

    @Column(name = "source_field", nullable = false)
    private String sourceField;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConfidenceLevel confidence = ConfidenceLevel.HIGH;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Dependency() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public ApiEndpoint getProducerEndpoint() { return producerEndpoint; }
    public void setProducerEndpoint(ApiEndpoint producerEndpoint) { this.producerEndpoint = producerEndpoint; }

    public ApiEndpoint getConsumerEndpoint() { return consumerEndpoint; }
    public void setConsumerEndpoint(ApiEndpoint consumerEndpoint) { this.consumerEndpoint = consumerEndpoint; }

    public String getParameterName() { return parameterName; }
    public void setParameterName(String parameterName) { this.parameterName = parameterName; }

    public String getSourceField() { return sourceField; }
    public void setSourceField(String sourceField) { this.sourceField = sourceField; }

    public ConfidenceLevel getConfidence() { return confidence; }
    public void setConfidence(ConfidenceLevel confidence) { this.confidence = confidence; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
