package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents a deployment target environment (e.g. Staging, Production, Client QA).
 * Documented Reason: Enforces safety profiles (blocking DELETE in prod, rate limits) and isolates environment-specific credentials.
 */
@Entity
@Table(name = "environments")
public class Environment {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "base_url", nullable = false, length = 1024)
    private String baseUrl;

    @Column(name = "is_production", nullable = false)
    private boolean isProduction = false;

    @Column(name = "auth_type", length = 50)
    private String authType;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Convert(converter = com.syed.apiqa.safety.EncryptedStringConverter.class)
    @Column(name = "auth_credentials", columnDefinition = "TEXT")
    private String authCredentials;

    @Column(name = "custom_headers", columnDefinition = "TEXT")
    private String customHeaders;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Environment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public boolean isProduction() { return isProduction; }
    public void setProduction(boolean production) { isProduction = production; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getAuthCredentials() { return authCredentials; }
    public void setAuthCredentials(String authCredentials) { this.authCredentials = authCredentials; }

    public String getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(String customHeaders) { this.customHeaders = customHeaders; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
