package com.syed.apiqa.performance;

import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.Execution;
import com.syed.apiqa.domain.PerformanceMetric;
import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.persistence.PerformanceMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PerformanceAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceAnalyticsService.class);
    private final PerformanceMetricRepository performanceMetricRepository;

    public PerformanceAnalyticsService(PerformanceMetricRepository performanceMetricRepository) {
        this.performanceMetricRepository = performanceMetricRepository;
    }

    /**
     * Analyzes execution latencies across the entire test run and per endpoint.
     * Computes exact mathematical percentiles (min, max, avg, P50, P90, P95, P99).
     */
    @Transactional
    public PerformanceSummary analyzeAndPersistMetrics(TestRun testRun, List<Execution> executions) {
        if (executions == null || executions.isEmpty()) {
            log.info("No executions recorded for TestRun {}. Skipping performance analytics.", testRun.getId());
            return new PerformanceSummary(0, 0L, 0L, 0.0, 0L, 0L, 0L, 0L, Collections.emptyList());
        }

        // 1. Overall Run Percentiles
        List<Long> allLatencies = executions.stream()
                .map(Execution::getLatencyMs)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        PerformanceMetric overallMetric = computeMetric(testRun, null, allLatencies);
        performanceMetricRepository.save(overallMetric);

        // 2. Per-Endpoint Percentiles
        Map<String, ApiEndpoint> endpointMap = new LinkedHashMap<>();
        Map<String, List<Long>> endpointLatencies = new LinkedHashMap<>();
        for (Execution execution : executions) {
            if (execution.getTestStep() != null && execution.getTestStep().getApiEndpoint() != null && execution.getLatencyMs() != null) {
                ApiEndpoint ep = execution.getTestStep().getApiEndpoint();
                endpointMap.putIfAbsent(ep.getId(), ep);
                endpointLatencies.computeIfAbsent(ep.getId(), k -> new ArrayList<>())
                        .add(execution.getLatencyMs());
            }
        }

        List<EndpointPerformanceSnapshot> endpointSnapshots = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : endpointLatencies.entrySet()) {
            ApiEndpoint ep = endpointMap.get(entry.getKey());
            List<Long> latencies = entry.getValue().stream().sorted().collect(Collectors.toList());
            PerformanceMetric epMetric = computeMetric(testRun, ep, latencies);
            performanceMetricRepository.save(epMetric);

            endpointSnapshots.add(new EndpointPerformanceSnapshot(
                    ep.getId(),
                    ep.getMethod(),
                    ep.getPath(),
                    epMetric.getTotalSamples(),
                    epMetric.getMinLatencyMs(),
                    epMetric.getMaxLatencyMs(),
                    epMetric.getAvgLatencyMs(),
                    epMetric.getP50LatencyMs(),
                    epMetric.getP90LatencyMs(),
                    epMetric.getP95LatencyMs(),
                    epMetric.getP99LatencyMs()
            ));
        }

        log.info("Calculated performance analytics for TestRun {}: totalSamples={}, avg={}ms, P50={}ms, P95={}ms, P99={}ms across {} endpoints.",
                testRun.getId(), overallMetric.getTotalSamples(), overallMetric.getAvgLatencyMs(),
                overallMetric.getP50LatencyMs(), overallMetric.getP95LatencyMs(), overallMetric.getP99LatencyMs(),
                endpointSnapshots.size());

        return new PerformanceSummary(
                overallMetric.getTotalSamples(),
                overallMetric.getMinLatencyMs(),
                overallMetric.getMaxLatencyMs(),
                overallMetric.getAvgLatencyMs(),
                overallMetric.getP50LatencyMs(),
                overallMetric.getP90LatencyMs(),
                overallMetric.getP95LatencyMs(),
                overallMetric.getP99LatencyMs(),
                endpointSnapshots
        );
    }

    private PerformanceMetric computeMetric(TestRun run, ApiEndpoint endpoint, List<Long> sortedLatencies) {
        PerformanceMetric metric = new PerformanceMetric();
        metric.setId(UUID.randomUUID().toString());
        metric.setTestRun(run);
        metric.setApiEndpoint(endpoint);
        metric.setCreatedAt(OffsetDateTime.now());

        if (sortedLatencies.isEmpty()) {
            metric.setTotalSamples(0);
            metric.setMinLatencyMs(0L);
            metric.setMaxLatencyMs(0L);
            metric.setAvgLatencyMs(0.0);
            metric.setP50LatencyMs(0L);
            metric.setP90LatencyMs(0L);
            metric.setP95LatencyMs(0L);
            metric.setP99LatencyMs(0L);
            return metric;
        }

        int count = sortedLatencies.size();
        long min = sortedLatencies.get(0);
        long max = sortedLatencies.get(count - 1);
        double avg = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        avg = Math.round(avg * 100.0) / 100.0;

        metric.setTotalSamples(count);
        metric.setMinLatencyMs(min);
        metric.setMaxLatencyMs(max);
        metric.setAvgLatencyMs(avg);
        metric.setP50LatencyMs(calculatePercentile(sortedLatencies, 50));
        metric.setP90LatencyMs(calculatePercentile(sortedLatencies, 90));
        metric.setP95LatencyMs(calculatePercentile(sortedLatencies, 95));
        metric.setP99LatencyMs(calculatePercentile(sortedLatencies, 99));

        return metric;
    }

    /**
     * Computes the nearest-rank percentile for a sorted list of values.
     */
    public static long calculatePercentile(List<Long> sortedValues, int percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) return 0L;
        if (percentile <= 0) return sortedValues.get(0);
        if (percentile >= 100) return sortedValues.get(sortedValues.size() - 1);

        int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    public static class PerformanceSummary {
        private final int totalSamples;
        private final long minMs;
        private final long maxMs;
        private final double avgMs;
        private final long p50Ms;
        private final long p90Ms;
        private final long p95Ms;
        private final long p99Ms;
        private final List<EndpointPerformanceSnapshot> endpoints;

        public PerformanceSummary(int totalSamples, long minMs, long maxMs, double avgMs,
                                  long p50Ms, long p90Ms, long p95Ms, long p99Ms,
                                  List<EndpointPerformanceSnapshot> endpoints) {
            this.totalSamples = totalSamples;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.avgMs = avgMs;
            this.p50Ms = p50Ms;
            this.p90Ms = p90Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.endpoints = endpoints;
        }

        public int getTotalSamples() { return totalSamples; }
        public long getMinMs() { return minMs; }
        public long getMaxMs() { return maxMs; }
        public double getAvgMs() { return avgMs; }
        public long getP50Ms() { return p50Ms; }
        public long getP90Ms() { return p90Ms; }
        public long getP95Ms() { return p95Ms; }
        public long getP99Ms() { return p99Ms; }
        public List<EndpointPerformanceSnapshot> getEndpoints() { return endpoints; }
    }

    public static class EndpointPerformanceSnapshot {
        private final String endpointId;
        private final String method;
        private final String path;
        private final int samples;
        private final long minMs;
        private final long maxMs;
        private final double avgMs;
        private final long p50Ms;
        private final long p90Ms;
        private final long p95Ms;
        private final long p99Ms;

        public EndpointPerformanceSnapshot(String endpointId, String method, String path, int samples,
                                           long minMs, long maxMs, double avgMs,
                                           long p50Ms, long p90Ms, long p95Ms, long p99Ms) {
            this.endpointId = endpointId;
            this.method = method;
            this.path = path;
            this.samples = samples;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.avgMs = avgMs;
            this.p50Ms = p50Ms;
            this.p90Ms = p90Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
        }

        public String getEndpointId() { return endpointId; }
        public String getMethod() { return method; }
        public String getPath() { return path; }
        public int getSamples() { return samples; }
        public long getMinMs() { return minMs; }
        public long getMaxMs() { return maxMs; }
        public double getAvgMs() { return avgMs; }
        public long getP50Ms() { return p50Ms; }
        public long getP90Ms() { return p90Ms; }
        public long getP95Ms() { return p95Ms; }
        public long getP99Ms() { return p99Ms; }
    }
}
