package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents a planned test scenario (e.g. CRUD workflow, single operation contract check, edge case).
 * Documented Reason: Groups correlated steps together and defines business scenario boundaries.
 */
@Entity
@Table(name = "test_cases")
public class TestCase {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "scenario_type", nullable = false, length = 50)
    private String scenarioType; // e.g. CRUD_WORKFLOW, SINGLE_ENDPOINT

    @Column(name = "category", length = 32)
    private String category = "POSITIVE_CRUD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StepStatus status = StepStatus.PENDING;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public TestCase() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }

    public int getExecutionOrder() { return executionOrder; }
    public void setExecutionOrder(int executionOrder) { this.executionOrder = executionOrder; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
