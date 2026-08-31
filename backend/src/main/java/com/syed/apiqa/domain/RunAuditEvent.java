package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable lifecycle audit record capturing every state transition and operational event.
 * Documented Reason: Provides forensic audit trail for run creation, starts, cancellations,
 * pauses, resumes, completions, timeouts, and crash recoveries without exposing credentials.
 */
@Entity
@Table(name = "run_audit_events")
public class RunAuditEvent {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(length = 64, nullable = false)
    private String actor;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public RunAuditEvent() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = OffsetDateTime.now();
    }

    public RunAuditEvent(TestRun testRun, String eventType, String actor, String details) {
        this();
        this.testRun = testRun;
        this.eventType = eventType;
        this.actor = actor;
        this.details = details;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
