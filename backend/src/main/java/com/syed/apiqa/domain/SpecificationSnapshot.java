package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Immutable persistent snapshot of an OpenAPI specification associated with a single TestRun.
 * Preserves the normalized contract in run-scoped memory.
 */
@Entity
@Table(name = "specification_snapshots")
public class SpecificationSnapshot implements Serializable {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @Column(name = "original_url", length = 1024)
    private String originalUrl;

    @Column(name = "resolved_spec_url", length = 1024)
    private String resolvedSpecUrl;

    @Column(name = "openapi_version", length = 50)
    private String openapiVersion;

    @Column(name = "base_url", length = 1024)
    private String baseUrl;

    @Column(name = "endpoints_count")
    private Integer endpointsCount = 0;

    @Lob
    @Column(name = "spec_json")
    private String specJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public SpecificationSnapshot() {}

    public SpecificationSnapshot(String id, TestRun testRun, String originalUrl, String resolvedSpecUrl,
                                 String openapiVersion, String baseUrl, Integer endpointsCount, String specJson) {
        this.id = id;
        this.testRun = testRun;
        this.originalUrl = originalUrl;
        this.resolvedSpecUrl = resolvedSpecUrl;
        this.openapiVersion = openapiVersion;
        this.baseUrl = baseUrl;
        this.endpointsCount = endpointsCount;
        this.specJson = specJson;
        this.createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }

    public String getResolvedSpecUrl() { return resolvedSpecUrl; }
    public void setResolvedSpecUrl(String resolvedSpecUrl) { this.resolvedSpecUrl = resolvedSpecUrl; }

    public String getOpenapiVersion() { return openapiVersion; }
    public void setOpenapiVersion(String openapiVersion) { this.openapiVersion = openapiVersion; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Integer getEndpointsCount() { return endpointsCount; }
    public void setEndpointsCount(Integer endpointsCount) { this.endpointsCount = endpointsCount; }

    public String getSpecJson() { return specJson; }
    public void setSpecJson(String specJson) { this.specJson = specJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
