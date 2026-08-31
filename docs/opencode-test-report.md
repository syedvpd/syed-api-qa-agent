# OpenCode Test Report — Syed API QA Agent (Phase 1 + Phase 2)

- **Date:** 2026-08-31
- **Command:** `mvn -o test` in `backend/` (offline, Java 21, Spring Boot 3.3.4, H2 test DB).
- **Result:** `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**.
- No external/real APIs were contacted; all mocks use WireMock bound to `127.0.0.1`.

---

## 1. Summary of test classes

| Class | Tests | Runs? | Coverage focus |
|---|---|---|---|
| `SyedApiQaApplicationTests` | 1 | ✅ | Spring context loads |
| `safety.SsrfProtectionGuardTest` | 5 | ✅ | Guard unit behavior (blocks loopback/private/metadata, protocol) |
| `safety.SecretMaskerTest` | 2 | ✅ | Secret masking for headers/bodies |
| `Phase1PipelineIntegrationTest` | 1 | ✅ | Full spec→discover→plan→execute→assert→report; variable propagation; CRUD |
| `Phase1FailureAndEdgeCasesTest` | 1 | ✅ | Failure isolation, dependent BLOCKED, independent branch continues |
| `Phase2AdvancedPipelineTest` | 2 | ✅ | Dynamic auth 401→refresh; reverse-topo cleanup; PRODUCTION cleanup SKIPPED |

**Verified observations:**
- The full CRUD flow (create → get → patch → delete → 404-after-delete) executes and the HTML
  report is generated and persists (`Phase1PipelineIntegrationTest`).
- A 500 on POST produces exactly 1 failed test, dependent GET is BLOCKED with reason, and the
  independent `/health` still PASSED (`Phase1FailureAndEdgeCasesTest`).
- Dynamic auth: login called, refresh called after 401, retried GET uses refreshed token
  (`Phase2AdvancedPipelineTest`, WireMock request verification).
- PRODUCTION mode suppresses destructive cleanup: `cleanupStatus=SKIPPED` with reason mentioning
  PRODUCTION (`Phase2AdvancedPipelineTest`).

---

## 2. Confidence / coverage gaps

| Gap | Severity | Details |
|---|---|---|
| SSRF guard never exercised end-to-end | **CRIT** | `application-test.yml:22` sets `ssrf-protection-enabled: false`; targets are `127.0.0.1`. Only unit tests cover the guard. No test proves a real plan-target hostname is blocked at execution time. |
| No auth/authorization tests | **CRIT** | No Spring Security; no tests assert unauthenticated requests are rejected. |
| No secret-redaction test on API reads | **CRIT** | `GET /api/runs` / `GET /api/runs/{id}` return `authLoginPayload`; no test asserts it is absent/null. |
| No concurrency-bound test | HIGH | No test asserts concurrent runs are capped (`max-concurrency` unused). |
| No DNS-rebinding/TOCTOU test | HIGH | See security report CRIT-2. |
| No retry-policy unit test (verb matrix) | MED | Retry logic (GET retried, POST not) is only indirectly covered via happy path; make it explicit. |
| No deterministic-data test for date/time | MED | Generator uses `now()` → non-reproducible; no assertion on reproducibility. |
| Frontend untested | MED | No component/integration tests; API origin is hardcoded. |
| Production-mode run reports `Failed: 3, Blocked: 9` | LOW | In PROD mode DELETE-dependent steps fail/block by design; test only asserts cleanup status, not the noise in pass/fail counters. |

---

## 3. Numerical run evidence (from `mvn -o test`)

- `Phase2AdvancedPipelineTest#testComplete...`: `Passed: 10, Failed: 2, Blocked: 8` (completed).
- `Phase2AdvancedPipelineTest#testProductionMode...`: `Passed: 8, Failed: 3, Blocked: 9`
  (DELETE/404 verification is deliberately suppressed/totally blocked in PRODUCTION — see note below).
- `Phase1PipelineIntegrationTest`: `Passed: 15, Failed: 0, Blocked: 0`.
- `Phase1FailureAndEdgeCasesTest`: `Passed: 1, Failed: 1, Blocked: 1` (as asserted).

> **Note on the PRODUCTION 404 step:** the Phase 2 "VERIFY 404 AFTER DELETE" step FAILS in the
> production-mode test because DELETE is skipped, so the resource is never removed and the 404 is
> not observed. This produces misleading "failed/blocked" counts in an otherwise intended
> PRODUCTION-safe mode — a counter-accuracy issue (see main audit). The run itself still completes.

---

## 4. Conclusion

Tests confirm the happy-path pipeline and several Phase 1/2 mechanisms work as designed.
However, **coverage is not production-safety proof**: the critical security surfaces (SSRF at
execution time, auth/authz, secret redaction, concurrency cap) are untested, and the SSRF guard is
deliberately disabled in the integration tests. **Verdict on test readiness: NOT READY** — the
missing security regression tests are prerequisites.
