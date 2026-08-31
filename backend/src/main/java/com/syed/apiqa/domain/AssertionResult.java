package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents the outcome of an individual assertion rule evaluated against an execution response.
 * Documented Reason: Granular validation auditing (status code, schema, required fields).
 */
@Entity
@Table(name = "assertion_results")
public class AssertionResult {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @Enumerated(EnumType.STRING)
    @Column(name = "assertion_type", nullable = false, length = 50)
    private AssertionType assertionType;

    @Column(name = "target_field")
    private String targetField;

    @Column(name = "expected_value", columnDefinition = "TEXT")
    private String expectedValue;

    @Column(name = "actual_value", columnDefinition = "TEXT")
    private String actualValue;

    @Column(nullable = false)
    private boolean passed;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public AssertionResult() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }

    public AssertionType getAssertionType() { return assertionType; }
    public void setAssertionType(AssertionType assertionType) { this.assertionType = assertionType; }

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

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
