# Independent Architecture & Risk Review — Syed API QA Agent

**Reviewer:** Independent Senior Backend Architect / QA Automation Architect / Security Engineer / Production Reliability Reviewer
**Date:** 2026-08-31
**Scope:** Full repository inspection, build verification, requirements cross-check.
**Method:** Static inspection of all source, config, docs, schema, frontend; `mvn test` build verification (8/8 passing).

---

## 0. What Was Actually Inspected (Evidence Base)

| Area | Files |
| :--- | :--- |
| Backend build | `pom.xml` (Spring Boot 3.3.4, Java 21, swagger-parser 2.1.22, json-schema-validator 1.4.0, wiremock 3.9.1, testcontainers) |
| Config | `application.yml`, `application-test.yml` |
| Schema | `V1__initial_schema.sql` (185 lines, 12 tables + 9 indexes) |
| Domain model | 17 entity/enum classes in `domain/` |
| Repositories | 8 Spring Data repositories in `persistence/` |
| Controllers | `TestRunController`, `HealthController` |
| Safety | `SsrfProtectionGuard`, `SecretMasker` + 2 test classes |
| Other | `WebConfig`, `SyedApiQaApplication` |
| Frontend | `package.json`, `next.config.js`, 6 pages in `frontend/src/app` |
| Docs | 11 architecture/spec documents in `docs/` |

**Build verification:** `mvn -o test` → **BUILD SUCCESS, 8 tests, 0 failures** (context loads + 5 SSRF guard tests + 2 secret masker tests). Phase 0 foundation compiles and its limited unit tests pass.

---

## 1. Executive Summary

The repository is a **clean, well-documented Phase 0 Foundation scaffold**, not yet a functioning API testing product.

**What exists and is solid:**
- A comprehensive, rational 11-doc architecture (zero-LLM, DAG dependency planner, retry-safety matrix, failure model, production safety profiles, perf/PDF reporting) — the *design* is genuinely senior-quality and internally consistent.
- A normalized JPA domain model (RunStatus + 13 StepStatus states, Dependency with ConfidenceLevel, Execution, Failure, PerformanceMetric, CapturedVariable) that maps 1:1 to the documented failure/state model.
- A valid PostgreSQL migration (12 tables, 9 indexes, FK cascade rules, `ON DELETE CASCADE/SET NULL` designed for run isolation).
- SSRF guard and SecretMasker implemented with unit tests. These match the safety doc's intent and pass tests.
- The backend builds and the test context loads under H2.

**What is missing — and it is the entire product.**
The autonomous engine is **completely unimplemented**. Of the 11 modules in `architecture.md` (`discovery`, `planning`, `generation`, `execution`, `assertion`, `performance`, `analysis`, `agent`, `run`, `reporting`), **zero** exist as code. The swagger-parser dependency is declared but never used. There is no HTTP execution (`RestClient` absent), no SSE (`SseEmitter` absent), no async run manager, no planner, no generator, no assertion, no reporting, no PDF, no authentication module. The `TestRunController.createRun` explicitly registers a run and **stops** (returns `PHASE_0_FOUNDATION_REGISTERED`).

Per `phase-plan.md`, the implementation is legitimately still in **Phase 0**, and Phase 1 (the actual end-to-end test engine) has not begun. That is consistent — but it means **not a single product requirement** in the PRD (§4.1–4.6) is functional yet.

**Because the autonomous engine does not exist, most of the 20 review dimensions cannot be "verified" — they are unstarted.** This review therefore separates:
1. **Concrete defects found in the existing Phase 0 code** (schema/entity drift, CORS, missing auth, plaintext secrets, SSRF design weakness).
2. **Design-level risks that must be engineered into Phases 1–6** to avoid the exact production failure modes the product is most exposed to (a tool whose job is to send DELETE/POST to live client backends).

---

## 2. Overall Architecture Assessment

**Sound design, dangerously early implementation.** The architecture is appropriate for the problem (modular monolith, PostgreSQL, deterministic engines, SSE for disconnect tolerance, SSRF-first). The critical observation: **this is a product whose entire value and entire risk are in the execution engine, and that engine does not exist yet.** The foundation is serviceable, but it must not be mistaken for a working or safe system until Phase 1 delivers the execution path with the safety gates enforced in code (they currently exist only as config values and documentation).

---

## 3. Critical Issues

> Breaking or genuinely dangerous. Any one of these blocks a READY verdict.

### C-1. The Autonomous Execution Engine Does Not Exist
- **Severity:** Critical (blocking)
- **Problem:** The core product — OpenAPI discovery → planning → data generation → HTTP execution → variable reuse → assertions → cleanup → report → PDF — is unimplemented. No package exists for discovery/planning/generation/execution/assertion/performance/analysis/agent/run/reporting. `swagger-parser-v3` and `json-schema-validator` are in `pom.xml` but referenced nowhere. `TestRunController.createRun` persists a row and returns a "Phase 0" placeholder (TestRunController.java:63-70).
- **Evidence:** Package tree contains only `api`, `config`, `domain`, `persistence`, `safety`. Grep for `SseEmitter`, `RestClient`, `Executor`, `OpenAPIParser` → zero engine matches. `HealthController` reports `phase = PHASE_0_FOUNDATION`.
- **Impact:** The application cannot test a single API. Full functionality absent.
- **Recommended fix:** This is expected per the phase plan. Proceed to Phase 1: implement each module against the already-correct architecture docs, and enforce every safety gate (C-3, H-1, H-2, H-3) in that engine before it can issue any network request.

### C-2. Backend Has No Authentication / Authorization Whatsoever
- **Severity:** Critical
- **Problem:** There is no Spring Security dependency, no security package, no auth filter, and no tenant/user ownership on `TestRun`. The controllers are anonymous-open. `WebConfig` sets CORS to `allowedOriginPatterns("*")` **with** `allowCredentials(true)`.
- **Evidence:** `pom.xml` has no `spring-boot-starter-security`. `TestRunController`/`HealthController` have no principal/ownership checks. `WebConfig.java:13-16` (wildcard origins + credentials). Architecture doc's `com.syed.apiqa.security` module does not exist.
- **Impact:** Once Phase 1 adds execution, **any anonymous caller — including a browser on any website — could trigger arbitrary test runs**, which dispatch POST/PUT/DELETE against the SSRF-vetted external hosts. Combined with CORS `*` + credentials, this is a recipe for a cross-site request forgery (CSRF) / drive-by harness over a live API. Multiple simultaneous anonymous users also directly violate the PRD's concurrency isolation intent (Review §16).
- **Recommended fix:** Before enabling execution, add authentication (even a simple API-key / self-signed cookie session for V1), enforce per-user ownership on runs (add `owner_id`), and restrict CORS to explicit allowed origins (never `*` with credentials).

### C-3. Auth Credentials Stored in Plaintext at Rest
- **Severity:** Critical
- **Problem:** `Environment.auth_credentials` is a plain `TEXT` column holding raw Bearer tokens / API keys / basic-auth strings. Nothing encrypts or masks it at rest. The flow from the frontend form to this column is plaintext end-to-end.
- **Evidence:** `Environment.java:34-35` (`auth_credentials`, `columnDefinition = "TEXT"`); `V1__initial_schema.sql:21`; frontend `new-run/page.tsx:119-127` collects the raw token and sends it over HTTP to `http://localhost:8080` (plaintext transport, see H-4).
- **Impact:** Anyone with DB read access (or a leaked backup/log) obtains live production credentials for the client systems the tool tests. This is a high-value secret store.
- **Recommended fix:** Encrypt credentials at rest (e.g., application-level AES-GCM with an env-provided key, or a KMS). Add a "credentials stored" flag and never echo them back through the API. Enforce HTTPS everywhere.

### C-4. SSRF Guard Is DNS-Rebinding-Vulnerable and Only Covers the Spec URL
- **Severity:** Critical (security design)
- **Problem:** The guard validates host IPs at one moment via `InetAddress.getAllByName`, but nothing pins the validated IP. When the real HTTP client resolves the hostname again at dispatch time, **DNS rebinding / TOCTOU** can deliver `localhost` or `169.254.169.254` to the client after the check passed. Equally important, the guard is invoked **only** for the OpenAPI URL (TestRunController.java:50). The per-endpoint `target_base_url` / generated URLs that the Phase 1 executor will call are **not** reference-checked at all today, and the hypothetical allowlist described in `safety.md` is not implemented in code.
- **Evidence:** `SsrfProtectionGuard.java:62` resolves to IPs but no IP is retained/used for the connection. `TestRunController.java:50` is the sole call site. `safety.md §2.3` (allowlist) has no code. SSL also not handled (an `https://host-with-evil-ssl` isn't an SSRF vector, but intermediate/redirect following isn't guarded either).
- **Impact:** A malicious user could point the tool at a hostname that resolves to a public IP for the check, then to the cloud metadata service or an internal host for the actual request — letting a public QA tool become an SSRF/port-scan/read bridge into the operator's/internal network.
- **Recommended fix:** When the executor is built, it must (a) resolve DNS once, (b) open the socket **to that resolved IP** while sending the original Host header, or otherwise pin to the validated address; (c) drive **all** target URLs (spec fetch and every execution URL) through the same guard; (d) apply a host allowlist; (e) not follow redirects to a re-validated host. Add regression tests for DNS-rebinding vectors and redirect-to-private-host.

---

## 4. High Priority Issues

### H-1. Retry / Timeout / Size / Destructive-Verb Safety Exist Only as Config, Never Enforced
- **Severity:** High
- **Problem:** `application.yml` declares `default-timeout-seconds: 15`, `max-response-size-bytes: 2097152`, `production-delete-enabled: false`, `max-concurrency: 10`. **No code reads or enforces any of these.** There is no RestClient, no timeout wiring, no response truncation, no verb gate. The critical retry rule from `execution-engine.md` ("POST never retried on timeout") is documentation only.
- **Impact:** When Phase 1 lands naively, the #1 product hazard materializes: a timed-out `POST /orders` retried automatically → duplicate orders / double billing on a live backend; unbounded response bodies → OOM; DELETE in production with no confirmation → data loss.
- **Recommended fix:** Enforce all four knobs inside the executor before dispatch: per-verb retry policy (GET/HEAD/OPTIONS only), response size cap with streaming truncation, timeout on the HTTP client, and a hard `DELETE` gate in PRODUCTION mode requiring explicit per-run confirmation. Unit-test each.

### H-2. Schema ↔ Entity Type Mismatches (JSONB vs TEXT) Risk Production Startup Failure
- **Severity:** High
- **Problem:** `V1__initial_schema.sql` declares `test_steps.request_headers JSONB`, `test_steps.request_body JSONB`, `api_endpoints.* JSONB`, `failure.evidence JSONB`, `environments.custom_headers JSONB`; but entities map these as `String`/`TEXT` (`TestStep.requestHeaders` TEXT, `ApiEndpoint.tags` TEXT, `Failure.evidence` TEXT, `Environment.customHeaders` TEXT). With `ddl-auto: validate` (application.yml:14) against real PostgreSQL, column-type mismatches between Hibernate's inferred type and the actual JSONB column can **fail context startup / persistence** — or silently serialize as text.
- **Evidence:** application.yml:14 `ddl-auto: validate`; V1 schema lines 21-22, 51-60, 148; entity files cited above. Tests don't catch this because the test profile uses H2 `create-drop` **with Flyway disabled** (application-test.yml:17-18), so the real migration is never executed in CI.
- **Impact:** Deploy-time failure or JSON columns written/read as opaque text, breaking evidence storage and reporting.
- **Recommended fix:** Either map JSONB columns as `String` + `columnDefinition="jsonb"` and serialize via a converter, or change the migration columns to `TEXT`. **Critically, enable Flyway + Testcontainers-Postgres in tests** so the real migration is validated against PostgreSQL in CI (see L-2).

### H-3. No Run-Level Execution State / ConcurrencyControls Implemented; Isolation Not Enforced
- **Severity:** High
- **Problem:** The architecture promises per-run isolation (variables, capture, auth, cleanup, execution) and bounded concurrency, but none of it exists. There is no `@Async` run service, no executor pool, no run-scoped context, no locking. `CapturedVariable` is scoped by `test_run_id` only in schema, with no runtime enforcement of freshness or run isolation.
- **Impact:** In Phase 1, two concurrent runs could share static/global state if the executor is written carelessly (e.g., a shared map keyed by variable name), leaking `{{user.id}}` or auth state between runs — violating Review §16.
- **Recommended fix:** Implement a per-`TestRun` execution context bound to the run's ID, a dedicated bounded executor, and a run-scoped variable store. Add a concurrency test with two simultaneous runs verifying zero variable leakage.

### H-4. Frontend Uses Hardcoded `http://localhost:8080` and Zero TLS
- **Severity:** High
- **Problem:** `new-run/page.tsx:20` posts to `http://localhost:8080` (hardcoded, no env var) and the Bearer/API-key token is sent over plain HTTP. `deployment.md` also uses `NEXT_PUBLIC_API_URL: http://localhost:8080`. Browser mixed-content will also break this once either side is HTTPS.
- **Impact:** Credentials transmitted in cleartext; unusable as-is for a deployed (Vercel + free backend) configuration; mixed-content failures behind HTTPS.
- **Recommended fix:** Use `NEXT_PUBLIC_API_URL` env on the client, default to relative `/api` proxied through Next rewrite in production, and serve backend over HTTPS.

---

## 5. Medium Priority Issues

### M-1. CORS Wildcard + Credentials (`*` + `allowCredentials(true)`)
- **Severity:** Medium (escalates to Critical once auth + execution exist)
- **Problem:** `allowedOriginPatterns("*")` with `allowCredentials(true)` is invalid for real credentials and, combined with the lack of auth (C-2), permits cross-site calls.
- **Evidence:** WebConfig.java:13-16.
- **Recommended fix:** Restrict origins to the actual frontend host and drop credentials unless same-origin or explicitly scoped.

### M-2. Environment/Production Determination Is Weak
- **Severity:** Medium
- **Problem:** The run accepts `environmentType` but `Environment` entity uses a separate `is_production` boolean and there is no mapping between the two. `EnvironmentType` defaults to STAGING, but nothing ties the destructive-verb gate to it.
- **Evidence:** TestRunController.java:55-58 (envType only persists; no safety behavior); Environment.java:29.
- **Recommended fix:** Centralize a single "is production / destructive allowed" resolution used by the safety gate, for both run and environment.

### M-3. `authType` Is Sent by Frontend but Ignored by Backend
- **Severity:** Medium
- **Problem:** `new-run` sends `authType` (and would send a token) in the run body; `createRun` only reads `openapiUrl` and `environmentType`. The token is neither stored, intended to be, nor validated.
- **Evidence:** new-run/page.tsx:23-27 vs TestRunController.java:41-42.
- **Recommended fix:** Decide the auth transport contract now (per-run token vs stored Environment credentials) so Phase 1 wires it consistently, and never persist unencrypted.

### M-4. `Failure.evidence` JSONB vs entity TEXT
- **Severity:** Medium
- **Problem:** Same JSONB/TEXT drift as H-2 but for `failures.evidence`.
- **Evidence:** V1:148; Failure.java:38-39.
- **Recommended fix:** Covered by H-2 fix; ensure evidence JSON is stored type-correctly.

### M-5. No Host Allowlist Implemented (Documented but Absent)
- **Severity:** Medium (ties to C-4)
- **Problem:** `safety.md §2.3` describes a target host allowlist; no code or config for it exists.
- **Recommended fix:** Implement allowlist config (`syed.safety.allowed-hosts`) and enforce in the executor.

---

## 6. Low Priority Issues

### L-1. `spring.jpa.open-in-view` Enabled (Performance Warning)
- **Problem:** Confirmed in build log ("open-in-view is enabled by default"). Deferred DB access during view rendering can hold connections and add latency under concurrent runs.
- **Fix:** Set `spring.jpa.open-in-view: false` and fetch eager/join what is needed for the run detail view.

### L-2. Tests Never Exercise the Real Migration
- **Problem:** Test profile: `ddl-auto: create-drop`, `flyway.enabled: false`, H2 `MODE=PostgreSQL`. The production `V1__initial_schema.sql` and real JSONB/Postgres behavior are never validated in CI (this is why H-2/M-4 slipped through).
- **Fix:** Add a Testcontainers-Postgres profile that runs Flyway against a real PG for at least the context-load test.

### L-3. Generated IDs Are JVM-side (`UUID.randomUUID().toString()` in controller)
- **Problem:** IDs created in app code, not DB (`gen_random_uuid()` or identity). Works but forces an extra write/read and complicates batch semantics.
- **Fix:** Prefer DB-generated UUIDs (`@GeneratedValue` with `uuid` strategy or PG `gen_random_uuid()`).

### L-4. Deprecation Warnings (Explicit Hibernate Dialect + Legacy PG dialect notice)
- **Problem:** Logs show `PostgreSQLDialect` no longer needs explicit declaration and the dialect reports an unsupported version string.
- **Fix:** Remove the explicit `hibernate.dialect` property; let Boot infer it.

### L-5. No Frontend Lint Baseline / No e2e
- **Problem:** `package.json` has `next lint` but no eslint config file or CI; no component tests at all; no backend controller integration test beyond context-load.
- **Fix:** Add ESLint config, a controller slice test, and one end-to-end run against a WireMock target once Phase 1 exists.

---

## 7. Missing Features (vs. the Product Contract)

Cross-referenced against `product-requirements.md`. **None of §4.1–4.6 are functional yet.**

| PRD Requirement | Status | Notes |
| :--- | :--- | :--- |
| OpenAPI 3.x / Swagger 2.x ingestion | **Not implemented** | Parser dep declared, unused. |
| Deterministic data generation (formats/constraints/nested/arrays/unique) | **Not implemented** | No `generation` package. |
| Dependency graph + `{{var}}` context | **Not implemented** | Domain `Dependency`/`CapturedVariable` exist; no engine. |
| HTTP execution (timeouts, pooling) | **Not implemented** | No RestClient. |
| Assertions (status/schema/fields/expected-404) | **Not implemented** | `AssertionType`/`AssertionResult` domain only. |
| Safe retry (POST never retried) | **Not implemented** | Docs only. |
| SSRF + private-IP protection | **Partially (spec URL only)** | Guard exists; execution scope + allowlist + rebinding fix missing. |
| Production destructive-verb gate | **Not implemented** | Config flag only. |
| Secret masking at rest / in logs | **Partial (in-transit serialization)** | `SecretMasker` exists but not wired to any persistence/log path; DB plaintext (C-3). |
| HTML + PDF report | **Not implemented** | No `reporting` module / PDF dep. |
| Latency P50/P90/P95/P99 + perf regression (Phases 3,6) | **Not implemented** | Domain/metric table exist. |
| SSE live stream / disconnect tolerance | **Not implemented** | No `SseEmitter`; frontend run page is a Phase-0 placeholder. |
| Historical regression / contract drift (Phase 6) | **Not implemented** | — |
| Cleanup (reverse-topological, CLEANUP_FAILED) | **Not implemented** | State enum exists. |
| Failure blast-radius isolation (BLOCKED etc.) | **Not implemented** | State enum exists. |

---

## 8. Security Findings

1. **[CRITICAL] No authentication on the backend itself** (C-2) — anonymous run creation; CSRF vector once execution exists.
2. **[CRITICAL] Credentials stored plaintext at rest** (C-3).
3. **[CRITICAL] SSRF DNS-rebinding + only-spec-URL coverage + no allowlist** (C-4). This is the class of flaw that lets a public QA tool reach `169.254.169.254` or internal hosts. **Highest-priority security fix before any execution ships.**
4. **[HIGH] CORS `*` + credentials** (M-1).
5. **[HIGH] Tokens over plaintext HTTP from browser** (H-4).
6. **[Positive] SecretMasker** correctly masks Authorization / X-Api-Key / sensitive JSON keys in the serialized payload path (tests pass). **Gap:** not wired to DB-at-rest or logs; the DB path stores raw credentials (C-3).

---

## 9. Performance Findings

1. **[HIGH] Concurrency/rate/size/timeout limits are config-only** (H-1) — no enforcement; a "performance test" could become an unbounded workload or a destructive load test against a live client backend. The `max-concurrency`, `max-response-size-bytes`, and per-verb retry settings must be enforced in the executor.
2. **[MEDIUM] No streaming/truncation path implemented** — `performance.md` promises bounded response handling; without it a huge response is an OOM risk in Phase 1.
3. **[LOW] No run-scoped thread pool tuned yet; `open-in-view` on** (L-1).
4. **Design is sound:** `System.nanoTime()` timing, percentile math, and the "never fabricate target timings" boundary are correctly specified; they just aren't coded.
5. **No historical regression storage/query** yet (Phase 6): the schema has `performance_metrics` and `test_runs`, so P95 comparison is buildable, but no interim-vs-current logic exists.

---

## 10. Edge Cases Currently Unhandled (to carry into Phase 1)

- **Malformed/empty/204 JSON responses** and **huge/slow responses**, **redirects to private hosts**, **connection reset** — no execution logic handles any of these yet.
- **Expected vs unexpected 404** (Review §10): the assertion model (`AssertionResult`/`expected_status` in `TestStep`) supports it structurally but no engine evaluates it; the "expected 404 after delete" inversion from `failure-model.md` is untested.
- **Unresolved variables → BLOCKED** (Review §4): documented, not implemented; must ensure a missing `{{user.id}}` never produces a malformed request or NPE.
- **POST timeout → UNCERTAIN_STATE, no auto-retry** (Review §6): critical, not implemented.
- **Cleanup after failure/timeout/partial run** and **reverse-dependency cleanup** (Review §12): documented, not implemented; `CLEANUP_FAILED` must not abort remaining cleanup.
- **Concurrent-run variable/auth/cleanup isolation** (Review §16): not implemented (H-3).
- **Deterministic seeding for reproducible runs** (Review §3): PRD requires it; no seed plumbing exists.
- **Cloud metadata via hostname** (e.g., `metadata.google.internal`) — guard blocks this literal host, but DNS-rebinding variants are open (C-4).
- **Secret leakage in query params** — `SecretMasker` handles headers/body keys but not secret-bearing query-string parameters (`?token=...`); if URLs are persisted (they are, `test_steps.resolved_url`), this is a leak path to close in Phase 1.

---

## 11. Recommended Changes (Prioritized)

1. **Before any execution ships (blocking):**
   - Implement per-execution SSRF with pinned-IP connection, per-URL validation (spec fetch + every execution target), host allowlist, and redirect-to-private-host blocking. Add DNS-rebinding regression tests (C-4).
   - Add authentication + per-user run ownership; restrict CORS to explicit origins (C-2, M-1).
   - Encrypt credentials at rest; stop persisting raw tokens (C-3).
2. **Phase 1 implementation order** (per the already-correct architecture): `discovery` → `generation` → `planning` → `execution` → `assertion` → `run` (SSE + async + bounded pool, run-scoped context) → `analysis`/`failure isolation` → `reporting` (HTML). Enforce H-1's timeout/size/verb/retry gates inside `execution` from day one.
3. **DB correctness:** unify JSONB/TEXT mapping; enable Flyway + Testcontainers-Postgres in CI (H-2, M-4, L-2).
4. **Frontend/deploy:** use `NEXT_PUBLIC_API_URL`, proxy `/api`, HTTPS (H-4); add ESLint + WireMock e2e once engine exists.
5. **Later phases as planned:** PDF (5), historical regression/contract drift (6), scheduling/automation (8).

---

## 12. Phase Recommendation

### NOT READY

The current phase is **Phase 0 — Foundation**, and within that phase the DoD is essentially satisfied (docs complete, compiles, schema present, zero-LLM audit clean, safety primitives + tests pass). **However:**

- It must **not** be considered production-ready, because the product's entire functional and risk surface (the autonomous execution engine) is unimplemented, and none of the Phase-1 safety gates (C-4, H-1) are enforced in code.
- Phase 1 (Production API Testing MVP) should not begin until C-2, C-3 (auth + at-rest encryption) and C-4 (execution-scoped SSRF) are added to the Phase-1 plan, because those three are the difference between a safe QA tool and a SSRF/CSRF/secret-leak liability pointed at live client backends.
- Re-review as READY only after Phase 1 delivers: end-to-end spec→plan→execute→assert→report, with enforced timeouts/retry-safety/response-size limits, destructive-verb gating, run-scoped isolation, and a WireMock/Testcontainers proof that a POST is never auto-retried and a production DELETE requires explicit confirmation.

---

*Reviewing agent note: This review made no code modifications and sent no requests to real APIs. Verification used the local build only.*
