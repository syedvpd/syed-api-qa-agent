# Syed API QA Agent — Phase 3 Implementation Plan: Performance Benchmarking & Historical Regression

## Objective
Implement Phase 3 of the autonomous API testing platform:
1. **Performance Engine & Latency Analytics**: Precise latency percentile tracking (min, max, avg, P50, P90, P95, P99), outlier identification, and concurrency benchmarking.
2. **Historical Regression Engine**: Multi-run baseline comparison detecting contract drift (new failures, missing endpoints, status drift) and latency regressions ($\Delta P95 > 25\%$).
3. **Full Zero-LLM Architecture**: Deterministic statistical calculations, nearest-rank percentiles, historical delta metrics.

---

## Architecture & Component Breakdown

### 1. Database Schema & Persistence
- Table `performance_metrics` already exists in `V1__init_schema.sql`:
  - `test_run_id`, `api_endpoint_id`, `min_latency_ms`, `max_latency_ms`, `avg_latency_ms`, `p50_latency_ms`, `p90_latency_ms`, `p95_latency_ms`, `p99_latency_ms`, `total_samples`, `created_at`.
- Add `PerformanceMetricRepository`:
  - `List<PerformanceMetric> findByTestRunId(String testRunId)`
  - `Optional<PerformanceMetric> findByTestRunIdAndApiEndpointId(String testRunId, String apiEndpointId)`
- Add historical baseline tracking to `TestRun` (e.g. `baselineRunId`, `regressionStatusJson`).

### 2. Performance Engine (`com.syed.apiqa.performance`)
- `PerformanceAnalyticsService`:
  - Collects execution latencies per endpoint and run-wide.
  - Computes exact mathematical percentiles using the nearest-rank method:
    - P50 (Median)
    - P90 (90th percentile)
    - P95 (95th percentile)
    - P99 (99th percentile)
  - Computes min, max, average, and standard deviation.
  - Identifies performance outliers (endpoints exceeding $2\times$ average latency or $> 1000\text{ms}$).
  - Persists `PerformanceMetric` entities for the test run and per endpoint.
- `ConcurrencyBenchmarkService`:
  - Executes safe, concurrent read requests (e.g. 5 concurrent probes on idempotent endpoints) to measure deployed backend latency under realistic multi-user load without overloading or crashing the client.

### 3. Historical Regression Engine (`com.syed.apiqa.regression`)
- `HistoricalRegressionService`:
  - Identifies baseline run: previous completed `TestRun` for the same `targetBaseUrl` / OpenAPI URL / environment.
  - Evaluates **Contract Drift**:
    - Previously passing endpoints that now FAIL.
    - Endpoints missing or removed from specification.
    - Status code changes.
  - Evaluates **Latency Regression**:
    - Compares current P95 vs baseline P95 per endpoint and overall.
    - Calculates percentage change: $\Delta \% = \frac{P95_{current} - P95_{baseline}}{P95_{baseline}} \times 100\%$.
    - Flags warning if $\Delta P95 > +25\%$, error if $> +50\%$.
  - Generates `RegressionReport` JSON attached to `TestRun` and available to reports and UI.

### 4. Integration into Run Pipeline (`RunManager`)
- Stage 1: Pre-Execution Auth
- Stage 2: Functional & Negative Executions
- Stage 3: Performance Analytics & Concurrency Benchmark
- Stage 4: Historical Regression Comparison
- Stage 5: Resource Teardown (Reverse-Topological)
- Stage 6: HTML Report Generation with Latency & Regression Matrix

### 5. API & UI Controller
- In `TestRunController.java`:
  - `GET /api/runs/{id}/performance`: returns run-level and endpoint-level latency percentiles.
  - `GET /api/runs/{id}/regression`: returns historical delta vs previous run.

---

## Verification Plan
1. **Automated Integration Suite (`Phase3PerformanceAndRegressionTest.java`)**:
   - Multi-sample latency test verifying percentile mathematical accuracy.
   - 2-run sequential scenario against WireMock:
     - Run 1: baseline (fast latency, 100% pass).
     - Run 2: regression (injected delay on an endpoint + 1 failing endpoint).
     - Assert `HistoricalRegressionService` correctly detects and flags the regression.
2. **Zero Regression on Existing Tests**:
   - `mvn test`: All 12 prior tests + new Phase 3 tests must pass with 0 failures, 0 errors.
3. **Frontend Verification**:
   - `npm run build`: 0 TypeScript or lint errors.
