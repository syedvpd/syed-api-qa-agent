# Edge-Case Architecture Review — Syed API QA Agent

This document evaluates the resilience and architectural defenses of **Syed API QA Agent** across 15 high-risk scenarios and extreme edge cases.

---

## 1. Edge-Case Scenarios & Defenses

### 1. Large-Scale OpenAPI Specs (500+ APIs)
- **Risk**: Memory bloat, slow parsing, graph explosion, thread exhaustion.
- **Architectural Defense**:
  - `swagger-parser-v3` resolves and normalizes schemas in-stream.
  - Operations are converted into lightweight, flattened `ApiEndpoint` domain records rather than retaining deep AST trees in heap.
  - Execution planning groups operations by resource tags, decomposing a 500+ endpoint spec into decoupled sub-graphs.

### 2. Thousands of Test Executions in a Single Run
- **Risk**: Database connection pool exhaustion, memory heap exhaustion from evidence storage.
- **Architectural Defense**:
  - Workflows run on a bounded `ThreadPoolTaskExecutor` with backpressure.
  - Step executions are written to PostgreSQL using batched writes or individual asynchronous writes with indexed foreign keys.
  - Response payloads are capped and bounded before persisting to prevent heap overflow.

### 3. Circular Dependencies ($A \to B \to A$)
- **Risk**: Infinite execution loops or dependency resolution deadlocks.
- **Architectural Defense**:
  - The Planning Engine applies Kahn's topological sort and Tarjan's strongly connected components algorithm during graph formulation.
  - When a directed cycle is detected, the engine breaks the circular edge by designating the downstream parameter to use synthetic schema-compliant mock data instead of waiting on a runtime variable.

### 4. Upstream Dependency Failure
- **Risk**: Cascading 404/500 errors across the entire test suite.
- **Architectural Defense**:
  - When a parent step (e.g. `POST /users`) fails or returns non-2xx, all downstream dependent child steps (e.g. `GET /users/{id}`, `DELETE /users/{id}`) are immediately transitioned to **`BLOCKED`** with cause `PREREQUISITE_FAILED`.
  - Execution immediately advances to the next independent operation branch.

### 5. POST Timeout (Uncertain Server Mutation)
- **Risk**: Retrying a timed-out POST may create duplicate database rows or duplicate financial charges.
- **Architectural Defense**:
  - The Execution Engine strictly enforces **Zero Automatic Retries** on `POST` requests.
  - A timed-out POST is marked `TIMEOUT` / `UNCERTAIN_STATE`. No subsequent dependent operations are assumed safe.

### 6. HTTP 429 Rate Limiting Encountered
- **Risk**: Target API throttles testing traffic, skewing failure metrics.
- **Architectural Defense**:
  - When HTTP 429 is received, the step is categorized specifically as `RATE_LIMITED` rather than a server error.
  - If `Retry-After` header is present, the worker pauses dynamically up to a configured threshold (e.g., 5 seconds) before proceeding, or backs off execution rate.

### 7. Malformed or Incomplete OpenAPI Specs
- **Risk**: Parser throws runtime exceptions or produces incomplete catalogs.
- **Architectural Defense**:
  - The Discovery Engine runs validation rules on the fetched document. If paths lack response schemas or parameters are missing types, default fallback primitives are applied.
  - Fatal schema errors immediately transition the run to `FAILED` with explicit contract error diagnostics.

### 8. Huge Response Payloads (> 10 MB JSON or binary dumps)
- **Risk**: JVM `OutOfMemoryError` during JSON parsing or report rendering.
- **Architectural Defense**:
  - Spring `RestClient` streams responses through a size-limiting filter.
  - Payloads exceeding `syed.safety.max-response-size-bytes` (default: 2 MB) are truncated before persisting in the database.
  - Schema assertion runs on stream tokens.

### 9. Browser Disconnect / Page Refresh During Active Test Run
- **Risk**: Aborting long-running tests or losing real-time visibility.
- **Architectural Defense**:
  - **Decoupled Execution Lifecycle**: The test run runs in background worker threads managed by the Spring Boot backend (`RunManager`).
  - Web clients observe via Server-Sent Events (`/api/runs/{id}/stream`).
  - If the browser closes, crashes, or disconnects, the test continues uninterrupted to completion. Reconnecting to `/runs/{id}` instantly re-subscribes to the ongoing execution state.

### 10. Concurrent Test Runs
- **Risk**: Database contention, CPU throttling, cross-talk between runs.
- **Architectural Defense**:
  - All entities (`ApiEndpoint`, `TestStep`, `CapturedVariable`, `Execution`) are strictly scoped by `test_run_id`.
  - Global concurrency is bounded by application configuration (`max-concurrency`).

### 11. Resource Cleanup Failures
- **Risk**: Leaving dirty test entities in the target system.
- **Architectural Defense**:
  - Cleanup executes in reverse topological order (children before parents).
  - If a `DELETE` call fails (e.g. 500 error), the failure is recorded as `CLEANUP_FAILED`, and the cleanup engine proceeds with remaining teardown tasks.

### 12. Production DELETE Safety
- **Risk**: Accidental deletion of live production data.
- **Architectural Defense**:
  - In `PRODUCTION` environment mode, all `DELETE` operations are **disabled by default**.
  - Explicit multi-step opt-in configuration is required to execute mutations in production.

### 13. SSRF & Private IP Infiltration
- **Risk**: Attackers entering `http://169.254.169.254` or `http://localhost:5432` to exfiltrate internal credentials.
- **Architectural Defense**:
  - `SsrfProtectionGuard` resolves DNS hostnames before socket connection.
  - Rejects loopback (`127.0.0.0/8`), private IP subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`), and non-HTTP protocols (`file://`).

### 14. Secret Leakage in Logs, Reports, or DB
- **Risk**: Exposing client API tokens, Authorization headers, or passwords.
- **Architectural Defense**:
  - `SecretMasker` intercepts all headers and JSON bodies prior to database storage and reporting, replacing sensitive values with `[REDACTED]`.

### 15. Database Failure or Backend Restart During Test Run
- **Risk**: Orphaned runs stuck in `EXECUTING` state forever.
- **Architectural Defense**:
  - On application startup, a reconciliation listener queries for runs in `EXECUTING` or `PLANNING` states and marks them as `FAILED` with reason `SERVER_RESTARTED_UNEXPECTEDLY`.
