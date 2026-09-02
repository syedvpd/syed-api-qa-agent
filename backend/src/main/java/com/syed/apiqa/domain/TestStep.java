package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Represents a single atomic execution unit within a test case (e.g. POST /users).
 * Documented Reason: Tracks variables, expected status, templates, and execution state of each discrete HTTP request.
 */
@Entity
@Table(name = "test_steps")
public class TestStep {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "api_endpoint_id")
    private ApiEndpoint apiEndpoint;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "path_template", nullable = false, length = 1024)
    private String pathTemplate;

    @Column(name = "resolved_url", length = 2048)
    private String resolvedUrl;

    @Column(name = "request_headers", columnDefinition = "TEXT")
    private String requestHeaders;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "expected_status")
    private Integer expectedStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StepStatus status = StepStatus.PENDING;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public TestStep() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestCase getTestCase() { return testCase; }
    public void setTestCase(TestCase testCase) { this.testCase = testCase; }

    public ApiEndpoint getApiEndpoint() { return apiEndpoint; }
    public void setApiEndpoint(ApiEndpoint apiEndpoint) { this.apiEndpoint = apiEndpoint; }

    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPathTemplate() { return pathTemplate; }
    public void setPathTemplate(String pathTemplate) { this.pathTemplate = pathTemplate; }

    public String getResolvedUrl() { return resolvedUrl; }
    public void setResolvedUrl(String resolvedUrl) { this.resolvedUrl = resolvedUrl; }

    public String getRequestHeaders() { return requestHeaders; }
    public void setRequestHeaders(String requestHeaders) { this.requestHeaders = requestHeaders; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public Integer getExpectedStatus() { return expectedStatus; }
    public void setExpectedStatus(Integer expectedStatus) { this.expectedStatus = expectedStatus; }

    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
