package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents an individual API operation extracted from the OpenAPI specification.
 * Documented Reason: Provides the catalog of operations, parameters, and contracts tested during a run.
 */
@Entity
@Table(name = "api_endpoints")
public class ApiEndpoint {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(name = "operation_id")
    private String operationId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "request_body_schema", columnDefinition = "TEXT")
    private String requestBodySchema;

    @Column(name = "response_schemas", columnDefinition = "TEXT")
    private String responseSchemas;

    @Column(name = "security_requirements", columnDefinition = "TEXT")
    private String securityRequirements;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public ApiEndpoint() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public String getRequestBodySchema() { return requestBodySchema; }
    public void setRequestBodySchema(String requestBodySchema) { this.requestBodySchema = requestBodySchema; }

    public String getResponseSchemas() { return responseSchemas; }
    public void setResponseSchemas(String responseSchemas) { this.responseSchemas = responseSchemas; }

    public String getSecurityRequirements() { return securityRequirements; }
    public void setSecurityRequirements(String securityRequirements) { this.securityRequirements = securityRequirements; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
