package com.syed.apiqa.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Stores calculated latency percentiles and distribution statistics for tested operations.
 * Documented Reason: Enables P50/P90/P95/P99 latency tracking and historical performance regression analysis.
 */
@Entity
@Table(name = "performance_metrics")
public class PerformanceMetric {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id")
    private ApiEndpoint apiEndpoint;

    @Column(name = "min_latency_ms", nullable = false)
    private Long minLatencyMs;

    @Column(name = "max_latency_ms", nullable = false)
    private Long maxLatencyMs;

    @Column(name = "avg_latency_ms", nullable = false)
    private Double avgLatencyMs;

    @Column(name = "p50_latency_ms", nullable = false)
    private Long p50LatencyMs;

    @Column(name = "p90_latency_ms", nullable = false)
    private Long p90LatencyMs;

    @Column(name = "p95_latency_ms", nullable = false)
    private Long p95LatencyMs;

    @Column(name = "p99_latency_ms", nullable = false)
    private Long p99LatencyMs;

    @Column(name = "total_samples", nullable = false)
    private int totalSamples;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public PerformanceMetric() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public TestRun getTestRun() { return testRun; }
    public void setTestRun(TestRun testRun) { this.testRun = testRun; }

    public ApiEndpoint getApiEndpoint() { return apiEndpoint; }
    public void setApiEndpoint(ApiEndpoint apiEndpoint) { this.apiEndpoint = apiEndpoint; }

    public Long getMinLatencyMs() { return minLatencyMs; }
    public void setMinLatencyMs(Long minLatencyMs) { this.minLatencyMs = minLatencyMs; }

    public Long getMaxLatencyMs() { return maxLatencyMs; }
    public void setMaxLatencyMs(Long maxLatencyMs) { this.maxLatencyMs = maxLatencyMs; }

    public Double getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }

    public Long getP50LatencyMs() { return p50LatencyMs; }
    public void setP50LatencyMs(Long p50LatencyMs) { this.p50LatencyMs = p50LatencyMs; }

    public Long getP90LatencyMs() { return p90LatencyMs; }
    public void setP90LatencyMs(Long p90LatencyMs) { this.p90LatencyMs = p90LatencyMs; }

    public Long getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(Long p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }

    public Long getP99LatencyMs() { return p99LatencyMs; }
    public void setP99LatencyMs(Long p99LatencyMs) { this.p99LatencyMs = p99LatencyMs; }

    public int getTotalSamples() { return totalSamples; }
    public void setTotalSamples(int totalSamples) { this.totalSamples = totalSamples; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
