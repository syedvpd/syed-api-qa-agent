# OpenCode Independent Audit — Deep Code Review (Phase 1 + Phase 2)

- **Auditor:** OpenCode (independent, from-source verification — no trust in prior reports)
- **Date:** 2026-08-31
- **Scope:** Full Phase 1 (discovery → planning → generation → execution → assertion → agent)
  and Phase 2 (dynamic auth, reverse-topological cleanup, advanced generation, reporting)
  implementation, vs `docs/architecture.md`, `phase-1-plan.md`, `phase-2-plan.md`,
  `requirements-traceability.md`.
- **Verification method:** Read every Java package, migration, config, test, frontend page,
  Dockerfile, docker-compose; ran `mvn -o test`.
- **Result:** 12/12 tests pass (`BUILD SUCCESS`), but multiple critical security defects
  and several functionality gaps remain. **Overall verdict: NOT READY.**

---

## 1. Build / Test Reality (verified, not assumed)

`mvn -o test` in `backend/` → `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`.

| Test | Count | Outcome |
|---|---|---|
| `SyedApiQaApplicationTests` | 1 | PASS (context loads) |
| `safety.SsrfProtectionGuardTest` | 5 | PASS (guard unit tests) |
| `safety.SecretMaskerTest` | 2 | PASS |
| `Phase1FailureAndEdgeCasesTest` | 1 | PASS |
| `Phase1PipelineIntegrationTest` | 1 | PASS (full CRUD + report) |
| `Phase2AdvancedPipelineTest` | 2 | PASS (auth refresh + prod-cleanup suppression) |

**Important caveat:** the WireMock integration tests run with `ssrf-protection-enabled: false`
(`src/test/resources/application-test.yml:22`) and target `http://127.0.0.1:<port>`. This is
required for the tests to work locally, but it means the SSRF guard is **never exercised
end-to-end against a real execution path**. Only the guard's own unit tests touch it.

---

## 2. Verified "Implemented" Claims (from source)

| Architecture claim (docs) | Reality in code | Status |
|---|---|---|
| Discovery (fetch + parse OpenAPI) | `OpenApiFetchService`, `OpenApiParserService`; `ApiEndpoint` persisted | ✅ present |
| Planning (CRUD + health) | `TestPlanService` builds cases/steps | ✅ present |
| Deterministic data generation | `DeterministicDataGenerator`, `NegativeDataGenerator` | ✅ present (flaws, see §4) |
| Execution w/ verb-aware retry | `HttpExecutionEngine` — only GET/HEAD/OPTIONS retried; POST uses maxAttempts=1 | ✅ safe |
| Response masking | `SecretMasker` (+ response body masking in engine) | ✅ |
| Assertion | `AssertionEngine` (status + schema + negative) | ✅ present |
| Failure isolation / agent | `FailureIsolationHandler`, case/step agent loop | ✅ present (flaw, see §4) |
| Cleanup (reverse-topo, prod-suppressed) | `ResourceCleanupManager` | ✅ present |
| Dynamic auth + 401 refresh | `DynamicAuthService`; RunManager refresh-on-401 | ✅ verified by test |
| SSE progress | `SseEventService`, `/api/runs/{id}/events` | ✅ present |
| HTML report | `HtmlReportGenerator` | ✅ verified by test |

---

## 3. CRITICAL — Security (also see `opencode-security-report.md`)

### C-1. Plaintext credential storage + unauthenticated exposure (new, critical)
- `TestRun.authLoginPayload` is a plain `TEXT` column containing the user-supplied login
  payload — typically `{"username":..., "password":...}`. (`domain/TestRun.java:73-74`,
  `resources/db/migration/V3__phase2_enhancements.sql:26`.)
- Set from raw request body with **no validation** (`api/TestRunController.java:89`).
- **Never nulled/redacted** after use.
- Exposed by **unauthenticated** `GET /api/runs` (`TestRunController.java:52-55`) — returns ALL
  runs — and `GET /api/runs/{id}` (`TestRunController.java:57-62`).
- The Phase 2 test itself persists plaintext `{"password":"secret_pass"}`
  (`Phase2AdvancedPipelineTest.java:183`).
- **Impact:** anyone who can reach the API can read every stored login payload (credentials) by
  ID enumeration / list. Critical. No at-rest encryption, no masking on read.

### C-2. SSRF DNS-rebinding / TOCTOU on every network path (confirmed)
- `SsrfProtectionGuard.validateTargetUrl` resolves and validates IPs via
  `InetAddress.getAllByName` (`safety/SsrfProtectionGuard.java:61-76`)…

### C-3. No authentication / authorization at all
- No Spring Security dependency (`pom.xml`). Every run endpoint is anonymously accessible
  (`TestRunController.java`). No user/ownership concept → any caller can trigger runs and read
  another user's endpoints/cases/report/cleanup (IDOR). `docs/deployment.md`/`safety.md` make no
  auth claims — feature genuinely absent.

### C-4. CORS wide open
- `config/WebConfig.java:12-17`: `allowedOriginPatterns("*")` + `allowCredentials(true)` +
  `allowedHeaders("*")`. Combined, effectively allows any origin to call authenticated endpoints.
  (Even without auth today, this becomes dangerous the moment auth is added.)

### C-5. `max-concurrency` and `production-delete-enabled` are dead config
- `resources/application.yml:28-29` declare `production-delete-enabled: false` and
  `max-concurrency: 10`; `.env.example:12-13` too.
- **No Java code reads** either key (grep-verified — only `default-timeout-seconds`,
  `max-response-size-bytes`, and `ssrf-protection-enabled` are consumed via `@Value`).
- `@Async` (`run/RunManager.java:81`) uses Spring's **unbounded default**
  `SimpleAsyncTaskExecutor` → concurrent test runs are **unlimited** regardless of config.

---

## 4. Functional gaps / correctness issues

### F-1. FailureIsolationHandler over-blocks (verified)
`agent/FailureIsolationHandler.isolateFailureAndBlockDownstream` marks **all** remaining
`PENDING` steps in a test case as `BLOCKED` on any single step failure — independent of
dependency graph. Independent negative variants sharing a case get blocked even though they do
not depend on the failed step. Safe (no cross-case over-blocking) but overly conservative within
a case. `Phase1FailureAndEdgeCasesTest` only asserts within-case behavior.

### F-2. Data generator non-determinism / edge bugs
- `DeterministicDataGenerator.generateString` uses `OffsetDateTime.now()`/`LocalDate.now()` for
  date/date-time → non-deterministic output (violates "deterministic" claim).
- Integer ranges use an exclusive max; `Math.abs(random.nextLong())` can yield negative
  (`Long.MIN_VALUE`).
- Array generation does not enforce `uniqueItems`.

### F-3. Hardcoded CRUD expectations cause false failures
`planning/TestPlanService` assumes POST→201, update→200, DELETE→204, verify-404→404. Many real
APIs differ (200-on-create, 202, etc.) → false-negative runs. Cleanup builds `DELETE /{path}/{id}`
by string-append to the POST path (brittle for non-/id-resource or nested paths).

### F-4. OpenAPI base-URL fallback landmine
`discovery/OpenApiParserService` falls back to `http://localhost:8080` when a spec has no
`servers`/origin — the SSRF guard would block it only when enabled; otherwise execution may target
localhost.

### F-5. Dynamic Auth has no UI, and authLogin* fields bypass SSRF
Frontend `new-run/page.tsx:23-27` only sends `openapiUrl, environmentType, authType` — never the
`authLoginUrl`/`authLoginPayload`. The fields are only reachable via raw API. The rewritten login
URL is passed to `DynamicAuthService`'s own HttpClient **without** re-running the SSRF guard.

---

## 5. Scorecard (strict)

| Area | Weight | Score | Evidence |
|---|---|---|---|
| Build & compile | 10% | ✅ PASS | `mvn -o test` success |
| Test coverage (real behavioral) | 20% | ⚠️ PARTIAL | 12 tests pass but SSRF disabled; no auth/IDOR/secret tests |
| Architecture/docs vs code | 15% | ⚠️ PARTIAL | Features present; determinism & isolation claims overstated |
| Safety (SSRF/retry/timeout/size/delete) | 20% | ❌ FAIL | DNS-rebinding TOCTOU; unbounded concurrency; dead config |
| Authorization / multi-tenancy | 15% | ❌ FAIL | No auth; IDOR; secrets exposed by list/get |
| Secrets at rest / in transit | 10% | ❌ FAIL | Plaintext `auth_login_payload`; no encryption |
| Deployment hardening (compose/env/frontend) | 10% | ⚠️ PARTIAL | Hardcoded DB creds; hardcoded `localhost:8080` frontend |

---

## 6. Verdict

### PRODUCTION READINESS: **NOT READY**

Blockers: **C-1 (plaintext credentials exposed), C-2 (SSRF DNS-rebinding on all paths),
C-3 (no auth / IDOR), C-5 (unbounded concurrency)**. None can be waived for production.

### Minimal path to CONDITIONALLY READY
1. Add authentication/authorization (Spring Security) with ownership checks on every run endpoint;
   never expose `auth_login_payload` in list/get responses; null or encrypt it at rest.
2. Close the SSRF TOCTOU — resolve once and connect to the resolved IP, or route connections
   through a single guarded client; apply the same guard to `DynamicAuthService`.
3. Wire a bounded task executor so `max-concurrency` is real.
4. Add automated regression tests proving: secrets redacted on read, IDOR returns 403, a POST is
   never auto-retried, production DELETE requires explicit confirmation.
5. Re-run full suite and re-audit.
