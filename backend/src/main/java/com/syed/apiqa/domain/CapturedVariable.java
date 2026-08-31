package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Stores dynamic variables extracted from HTTP response bodies/headers for subsequent parameter injection.
 * Documented Reason: Provides deterministic context state scoping (e.g. {{user.id}}) across workflow steps.
 */
@Entity
@Table(name = "captured_variables")
public class CapturedVariable {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id")
    private Execution execution;

    @Column(name = "variable_name", nullable = false)
    private String variableName;

    @Column(name = "variable_value", nullable = false, columnDefinition = "TEXT")
    private String variableValue;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public CapturedVariable() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }

    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }

    public String getVariableValue() { return variableValue; }
    public void setVariableValue(String variableValue) { this.variableValue = variableValue; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
