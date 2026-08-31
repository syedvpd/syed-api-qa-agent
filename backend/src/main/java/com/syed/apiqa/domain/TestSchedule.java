package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persisted entity configuring recurring automated test execution.
 * Documented Reason: Supports production test automation routines (daily, weekly, custom cron)
 * without babysitting runs.
 */
@Entity
@Table(name = "test_schedules")
public class TestSchedule {

    public enum ScheduleType {
        DAILY,
        WEEKLY,
        CUSTOM_CRON
    }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @Column(length = 256, nullable = false)
    private String name;

    @Column(name = "openapi_url", length = 2048, nullable = false)
    private String openapiUrl;

    @Column(length = 32, nullable = false)
    private String environment;

    @Column(name = "auth_type", length = 32)
    private String authType = "NONE";

    @Column(name = "auth_token", length = 1024)
    private String authToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", length = 32, nullable = false)
    private ScheduleType scheduleType;

    @Column(name = "cron_expression", length = 64)
    private String cronExpression;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public TestSchedule() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public TestSchedule(String ownerId, String name, String openapiUrl, String environment,
                        ScheduleType scheduleType, String cronExpression) {
        this();
        this.ownerId = ownerId;
        this.name = name;
        this.openapiUrl = openapiUrl;
        this.environment = environment;
        this.scheduleType = scheduleType;
        this.cronExpression = cronExpression;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOpenapiUrl() { return openapiUrl; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public ScheduleType getScheduleType() { return scheduleType; }
    public void setScheduleType(ScheduleType scheduleType) { this.scheduleType = scheduleType; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
