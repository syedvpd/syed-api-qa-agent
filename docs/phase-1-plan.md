# Phase 1 Implementation Plan — Production API Testing MVP

## 1. Objective & Scope

Phase 1 delivers the complete vertical slice of **Syed API QA Agent**:
Taking a live OpenAPI/Swagger URL for a deployed backend &rarr; Fetching & validating specification with SSRF defense &rarr; Parsing operations, parameters, schemas &rarr; Building API inventory &rarr; Inferring parameter dependencies & DAG &rarr; Synthesizing deterministic test data &rarr; Generating execution test plan (including stateful CRUD chains) &rarr; Executing real HTTP requests via `RestClient` with nanosecond latency capture &rarr; Extracting and dynamically propagating runtime variables &rarr; Evaluating declarative assertions (status, schema, required fields, headers) &rarr; Isolating failures so dependent steps become `BLOCKED` while independent branches continue &rarr; Managing background execution decoupled from browser &rarr; Streaming real-time SSE progress events &rarr; Recording full evidence with masked credentials in PostgreSQL &rarr; Generating an executive HTML audit report.

**Hard Rule**: Absolute zero external LLM dependencies. All algorithms are deterministic Java 21 implementations.

---

## 2. Implementation Sequence

The implementation follows a logical, layered progression:

1. **Database Schema Enhancements (Flyway V2)**:
   - Add `reason` column to `dependencies` table for auditability of why a dependency was inferred.
   - Align `RunStatus` values (`DISCOVERING`, `PLANNING`, `EXECUTING`, `CLEANUP`, `REPORTING`, `COMPLETED`, `FAILED`, `CANCELLED`).
2. **OpenAPI Fetcher & Discovery Engine (`com.syed.apiqa.discovery`)**:
   - `OpenApiFetchService`: Fetches raw spec from live URL over HTTP/HTTPS with strict pre-connection SSRF defense (`SsrfProtectionGuard`), connection/read timeouts (10s), 2MB size bounding, redirect safety, and content-type validation.
   - `OpenApiParserService`: Uses `io.swagger.parser.v3` to parse OpenAPI 3.x and Swagger 2.x; resolves `$ref` schemas safely; handles circular schema refs; extracts paths, methods, operations, tags, summaries, parameters (path, query, header), request body schemas, and response definitions into `ApiEndpoint` entities.
3. **Deterministic Test Data Generator (`com.syed.apiqa.generation`)**:
   - `DeterministicDataGenerator`: Generates realistic, schema-valid data using seeded pseudo-randomness per `TestRun` (guaranteeing reproducibility).
   - Supports: `string`, `integer`, `number`, `boolean`, `array`, `object`, `uuid`, `email`, `date`, `date-time`, `uri`, `enum`.
   - Enforces constraints: `required`, `nullable`, `minLength`, `maxLength`, `minimum`, `maximum`, `default`, `example`.
4. **Dependency Engine & DAG Planner (`com.syed.apiqa.planning`)**:
   - `DependencyEngine`: Evaluates path parameter templates (e.g. `{id}`, `{userId}`), response schemas, and entity tags to infer parent-child links with confidence ratings (`HIGH`, `MEDIUM`, `LOW`) and documented reasons.
   - Cycle detection: Detects cyclical dependencies using Kahn's algorithm; falls back to synthetic mock data for non-essential parameters.
   - `TestPlanService`: Formulates test cases and ordered steps:
     - CRUD lifecycle workflows (`POST` &rarr; `GET` &rarr; `PATCH`/`PUT` &rarr; `GET` &rarr; `DELETE` &rarr; `GET 404`).
     - Single-endpoint contract verification for independent routes (e.g., `GET /products`).
5. **Execution Engine & Safe HTTP Dispatch (`com.syed.apiqa.execution`)**:
   - `ExecutionContext`: Scoped variable store per `TestRun` (supporting `{{entity.variable}}` syntax).
   - Variable substitution in URL path, headers, query params, and JSON request bodies. If a variable is missing, marks step as `BLOCKED` with clear reason.
   - `HttpExecutionEngine`: Dispatches real HTTP requests with Spring `RestClient`.
   - Idempotency & Retry Safety: `GET` retryable with backoff; `POST` never automatically retried on timeout (`OUTCOME UNCERTAIN / RETRY SUPPRESSED`).
   - Rate limiting: Detects HTTP `429 Too Many Requests`, parses `Retry-After`, applies backoff.
   - Bounded response size: Enforces 2MB maximum response body limit.
   - Credential masking: Passes request/response headers and bodies through `SecretMasker`.
6. **Assertion Engine (`com.syed.apiqa.assertion`)**:
   - `AssertionEngine`: Validates HTTP status code against contract (including expected negative codes like 404 after deletion), content-type, required response body fields, and JSON schema compliance.
   - Records discrete `AssertionResult` items per execution.
7. **Failure Isolation & Blast-Radius Handler (`com.syed.apiqa.agent`)**:
   - When a step fails (e.g., `POST /users` returns 500):
     - Dependent downstream steps (`GET /users/{id}`, `PATCH /users/{id}`, `DELETE /users/{id}`) are marked `BLOCKED`.
     - Independent operations (`GET /products`) continue execution unaffected.
8. **Autonomous Run Manager & SSE Progress (`com.syed.apiqa.run`)**:
   - `RunManager`: Executes tests asynchronously in background worker threads. Decoupled from HTTP request lifecycle; if the user's browser closes or disconnects, the run continues to completion.
   - `SseEventService`: Publishes granular lifecycle events (`RUN_STARTED`, `DISCOVERY_PROGRESS`, `API_DISCOVERED`, `PLANNING_PROGRESS`, `TEST_STARTED`, `TEST_COMPLETED`, `TEST_FAILED`, `TEST_BLOCKED`, `REPORT_GENERATING`, `RUN_COMPLETED`) without exposing secrets.
9. **HTML Report Generator (`com.syed.apiqa.reporting`)**:
   - `HtmlReportGenerator`: Compiles persisted database evidence into a standalone, beautiful, responsive HTML report with executive KPI summary, endpoint coverage matrix, failure breakdown, latency stats, and sanitized request/response inspector.
10. **Frontend UI Pages (`frontend/src/app`)**:
    - Functional `/new-run`: Target OpenAPI URL, environment (Staging/Production), auth (None/Bearer/API Key/Basic), safety controls.
    - Functional `/runs/[id]/live`: Real-time SSE connection displaying live progress, running step, endpoint counters, latency timeline, and failure cards.
    - Functional `/runs/[id]/results`: Tabular endpoint execution matrix with request/response evidence drawer, assertion diffs, and filterable status tags.
    - Functional `/runs/[id]/report`: Embedded viewer and download trigger for the generated HTML report.

---

## 3. Database Changes (Flyway V2)

Create `backend/src/main/resources/db/migration/V2__phase1_enhancements.sql`:
- Add `reason TEXT` to `dependencies` table.
- Add `failure_reason TEXT` to `test_steps` table for immediate explanation of `BLOCKED` states.
- Ensure indexing on `dependencies(test_run_id)` and `test_steps(test_case_id, status)`.

---

## 4. API Endpoints

- `POST /api/runs`: Create and launch an autonomous test run in background.
- `GET /api/runs`: List recent test runs with execution KPIs.
- `GET /api/runs/{id}`: Fetch detailed test run state, counts, and duration.
- `GET /api/runs/{id}/endpoints`: Retrieve discovered OpenAPI endpoints inventory.
- `GET /api/runs/{id}/cases`: Retrieve planned test cases and steps.
- `GET /api/runs/{id}/executions`: Retrieve detailed step execution evidence, headers, payloads, latency, and assertion results.
- `GET /api/runs/{id}/report`: Retrieve the generated HTML report.
- `GET /api/runs/{id}/events`: Server-Sent Events (SSE) stream for real-time progress.

---

## 5. Testing & Verification Strategy

### Automated WireMock Integration Suite (`Phase1EndToEndTest.java`)
Spin up a live WireMock server acting as the deployed backend with:
1. `POST /users` &rarr; Returns `201 Created` with `{"id": "usr_99", "name": "...", "email": "..."}`
2. `GET /users/usr_99` &rarr; Returns `200 OK` with user details
3. `PATCH /users/usr_99` &rarr; Returns `200 OK` with updated fields
4. `DELETE /users/usr_99` &rarr; Returns `204 No Content`
5. `GET /users/usr_99` (after delete) &rarr; Returns `404 Not Found` (asserted as `PASSED`)
6. `GET /products` &rarr; Returns `200 OK` independent inventory list

### Specific Failure & Edge-Case Tests
1. **Upstream Failure Isolation**: WireMock returns `500` on `POST /users` &rarr; Verify `GET /users/{id}` and `DELETE /users/{id}` are marked `BLOCKED`, while `GET /products` executes and passes.
2. **POST Timeout & No-Retry Safety**: WireMock delays `POST` beyond timeout &rarr; Verify step marks `TIMEOUT`, zero retries are dispatched, and dependent steps are blocked.
3. **HTTP 429 Rate Limiting**: WireMock returns 429 with `Retry-After: 1` &rarr; Verify backoff is handled and status recorded.
4. **SSRF Blocking**: Verify attempting to run against `http://localhost:9999` or `http://169.254.169.254` is blocked before network socket creation.
5. **Secret Masking**: Verify Bearer tokens and passwords never appear in execution records, reports, or SSE payloads.
6. **Concurrent Runs**: Execute two runs concurrently; verify context variables and executions remain isolated by run ID.
7. **Browser Disconnect**: Start run, verify backend finishes run and saves report without active client.

---

## 6. Definition of Done (Phase 1)

All criteria from prompt Section 33 must be green and verified:
- [ ] Live OpenAPI URL accepted, environment selected, auth config supported
- [ ] OpenAPI fetched, SSRF protected, validated, parsed, inventory persisted
- [ ] Dependency graph built with confidence levels and reasons
- [ ] Deterministic test data generated adhering to schema constraints
- [ ] GET, POST, PUT/PATCH, and DELETE safely executed with idempotency rules
- [ ] Context variables captured and dynamically reused across steps
- [ ] Declarative assertions evaluate status, schema, required fields, and headers
- [ ] Failure isolation stops blast radius; independent tests continue
- [ ] 429 rate limiting and timeout policies enforced
- [ ] Autonomous backend execution with SSE streaming
- [ ] Full evidence stored in PostgreSQL with secrets masked
- [ ] HTML audit report generated from real execution data
- [ ] Comprehensive WireMock automated integration tests pass 100%
