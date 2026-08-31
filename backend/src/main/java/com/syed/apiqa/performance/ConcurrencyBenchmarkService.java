package com.syed.apiqa.performance;

import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.execution.HttpExecutionEngine;
import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.EnvironmentType;
import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.domain.TestStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class ConcurrencyBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyBenchmarkService.class);
    private final HttpExecutionEngine httpEngine;

    public ConcurrencyBenchmarkService(HttpExecutionEngine httpEngine) {
        this.httpEngine = httpEngine;
    }

    /**
     * Executes safe, lightweight concurrency probes on non-destructive (GET) endpoints.
     * Concurrency is bounded to avoid overwhelming client deployments (e.g. 5 concurrent requests, total 15 iterations).
     */
    public ConcurrencyReport runConcurrencyBenchmark(TestRun testRun,
                                                     List<ApiEndpoint> endpoints,
                                                     ExecutionContext context,
                                                     String authType,
                                                     String authCredentials,
                                                     int concurrencyLevel,
                                                     int totalRequests) {
        // Filter safe GET endpoints without path variables or with resolved variables
        List<ApiEndpoint> safeEndpoints = endpoints.stream()
                .filter(ep -> "GET".equalsIgnoreCase(ep.getMethod()))
                .collect(Collectors.toList());

        if (safeEndpoints.isEmpty()) {
            log.info("No safe GET endpoints available for concurrency benchmarking in TestRun {}", testRun.getId());
            return new ConcurrencyReport(concurrencyLevel, 0, 0, 0L, 0.0, 0L, 0.0);
        }

        ApiEndpoint targetEndpoint = safeEndpoints.get(0); // Select first safe endpoint
        int concurrency = Math.max(1, Math.min(concurrencyLevel, 10)); // Clamp between 1 and 10
        int requests = Math.max(concurrency, Math.min(totalRequests, 50)); // Clamp max 50

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<HttpExecutionEngine.StepExecutionOutcome>> futures = new ArrayList<>();

        long overallStart = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            TestStep benchStep = new TestStep();
            benchStep.setId(UUID.randomUUID().toString());
            benchStep.setApiEndpoint(targetEndpoint);
            benchStep.setStepOrder(i + 1);
            benchStep.setName("CONCURRENCY_BENCHMARK_" + (i + 1));
            benchStep.setMethod("GET");
            benchStep.setPathTemplate(targetEndpoint.getPath());
            benchStep.setExpectedStatus(200);

            futures.add(executor.submit(() -> httpEngine.executeStep(
                    benchStep,
                    testRun.getTargetBaseUrl(),
                    context,
                    testRun.getEnvironmentType(),
                    authType,
                    authCredentials
            )));
        }

        List<Long> latencies = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;

        for (Future<HttpExecutionEngine.StepExecutionOutcome> future : futures) {
            try {
                HttpExecutionEngine.StepExecutionOutcome outcome = future.get(10, TimeUnit.SECONDS);
                long lat = (outcome.getExecution() != null && outcome.getExecution().getLatencyMs() != null)
                        ? outcome.getExecution().getLatencyMs()
                        : 0L;
                latencies.add(lat);
                if (outcome.getFinalStatus() != null && "PASSED".equalsIgnoreCase(outcome.getFinalStatus().name())) {
                    successCount++;
                } else {
                    errorCount++;
                }
            } catch (Exception e) {
                errorCount++;
                latencies.add(10000L); // 10s penalty on timeout/failure
            }
        }
        executor.shutdown();

        long overallDurationMs = (System.nanoTime() - overallStart) / 1_000_000L;
        Collections.sort(latencies);

        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p95Latency = PerformanceAnalyticsService.calculatePercentile(latencies, 95);
        double throughputRps = overallDurationMs > 0 ? (requests * 1000.0) / overallDurationMs : 0.0;
        throughputRps = Math.round(throughputRps * 10.0) / 10.0;

        log.info("Concurrency benchmark completed for TestRun {}: {} requests at concurrency={}, throughput={} req/s, avgLatency={}ms, P95={}ms, errors={}",
                testRun.getId(), requests, concurrency, throughputRps, Math.round(avgLatency), p95Latency, errorCount);

        return new ConcurrencyReport(
                concurrency,
                requests,
                successCount,
                p95Latency,
                avgLatency,
                overallDurationMs,
                throughputRps
        );
    }

    public static class ConcurrencyReport {
        private final int concurrencyLevel;
        private final int totalRequests;
        private final int successfulRequests;
        private final long p95LatencyMs;
        private final double avgLatencyMs;
        private final long totalDurationMs;
        private final double throughputRps;

        public ConcurrencyReport(int concurrencyLevel, int totalRequests, int successfulRequests,
                                 long p95LatencyMs, double avgLatencyMs, long totalDurationMs, double throughputRps) {
            this.concurrencyLevel = concurrencyLevel;
            this.totalRequests = totalRequests;
            this.successfulRequests = successfulRequests;
            this.p95LatencyMs = p95LatencyMs;
            this.avgLatencyMs = Math.round(avgLatencyMs * 100.0) / 100.0;
            this.totalDurationMs = totalDurationMs;
            this.throughputRps = throughputRps;
        }

        public int getConcurrencyLevel() { return concurrencyLevel; }
        public int getTotalRequests() { return totalRequests; }
        public int getSuccessfulRequests() { return successfulRequests; }
        public long getP95LatencyMs() { return p95LatencyMs; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public long getTotalDurationMs() { return totalDurationMs; }
        public double getThroughputRps() { return throughputRps; }
    }
}
