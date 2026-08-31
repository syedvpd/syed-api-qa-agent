# Performance & Resource Management Architecture — Syed API QA Agent

## 1. Dual Perspective on Performance

In Syed API QA Agent, performance is evaluated along two distinct dimensions:
1. **Performance OF the Agent**: The efficiency, CPU/memory footprint, thread utilization, and responsiveness of Syed API QA Agent itself.
2. **Performance of the Target API**: Measuring and analyzing the latency, throughput, and stability of the client's deployed backend over HTTP/HTTPS.

---

## 2. Agent Resource Efficiency Guidelines

To ensure high performance without memory leaks or runaway resource consumption:
- **Bounded Thread Pools**: Execution uses a tuned `ThreadPoolTaskExecutor` with bounded worker capacity and backpressure queues.
- **Payload Size Truncation**: HTTP response bodies larger than 2 MB are truncated for storage and evidence rendering, while schema assertion evaluates on streaming tokens or byte buffers to prevent `OutOfMemoryError`.
- **Zero In-Memory Spec Bloat**: Parsed OpenAPI definitions are converted into compact domain models rather than retaining deep recursive AST objects in session memory.
- **Database Write Batching**: Execution step results are batched where appropriate, minimizing lock contention on the PostgreSQL instance.

---

## 3. Client API Latency & Metrics Collection

During each test run, every request's elapsed duration is measured using `System.nanoTime()` for microsecond precision and recorded in milliseconds.

The Performance Engine calculates:
- **Minimum Latency** ($L_{min}$)
- **Maximum Latency** ($L_{max}$)
- **Average Latency** ($L_{avg}$)
- **Percentiles**:
  - **P50** (Median latency)
  - **P90** (90th percentile)
  - **P95** (95th percentile)
  - **P99** (99th percentile, critical for tail-latency detection)

### Clear Boundary Principle
The agent measures **external round-trip HTTP latency**. It must **never fabricate or claim** database, cache, or internal microservice timings unless an explicit OpenTelemetry collector/trace context is attached in Phase 7.
