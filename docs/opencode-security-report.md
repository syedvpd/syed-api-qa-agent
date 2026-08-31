# OpenCode Security Report — Syed API QA Agent (Phase 1 + Phase 2)

- **Date:** 2026-08-31
- **Method:** source review + `mvn -o test`, no prior-report trust.
- **Severity guide:** CRIT = blocks production; HIGH = serious; MED = important; LOW = hygiene.

---

## CRIT-1 — Plaintext credentials stored & exposed unauthenticated
- **Files/lines:** `domain/TestRun.java:73-74` (`auth_login_payload TEXT`);
  `resources/db/migration/V3__phase2_enhancements.sql:26`;
  `api/TestRunController.java:89` (set from request), `:52-62` (list/get expose it);
  `run/RunManager.java:156` (consumed by `DynamicAuthService`).
- **Issue:** The login payload (JSON that includes a password) is persisted verbatim, never
  nulled or encrypted, and returned by **unauthenticated** `GET /api/runs` (all runs) and
  `GET /api/runs/{id}`.
- **Proof in code/test:** `Phase2AdvancedPipelineTest.java:183` persists
  `{"username":"qa_agent","password":"secret_pass"}`.
- **Risk:** Credential disclosure to any anonymous caller (ID enumeration). Critical.

## CRIT-2 — SSRF DNS-rebinding / TOCTOU on all execution paths
- **Files/lines:** `safety/SsrfProtectionGuard.java:61-76` resolves+validates IPs via
  `InetAddress.getAllByName`; actual connections each re-resolve independently:
  `execution/HttpExecutionEngine.java` (HttpClient), `discovery/OpenApiFetchService.java`
  (HttpURLConnection), `auth/DynamicAuthService.java` (HttpClient),
  `cleanup/ResourceCleanupManager.java` (HttpClient).
- **Issue:** The guard validates an IP that the connection never uses; a hostname can be switched
  (DNS rebinding) between validation and connect, reaching loopback/private/metadata despite the
  guard. Validation is decoupled from the connect in every client.
- **Also:** the auth login URL and cleanup URLs are not validated by the guard at all (only the
  `openapiUrl` is, in `TestRunController.java:77`).

## CRIT-3 — No authentication / authorization / IDOR
- **Files:** `api/TestRunController.java` (all endpoints public); no Spring Security in `pom.xml`.
- **Issue:** Any caller can create runs, and read any run's endpoints/cases/report/cleanup/SSE by
  ID. Multi-user isolation is entirely absent (IDOR). No CSRF posture, no rate limiting.

## CRIT-4 — Unbounded concurrency (config ignored)
- **Files:** `run/RunManager.java:81` `@Async` on default `SimpleAsyncTaskExecutor` (unbounded);
  `resources/application.yml:29` `max-concurrency: 10` is **not read anywhere** (grep-verified).
- **Issue:** N concurrent runs spawn N threads with no bound → resource exhaustion under load or
  abuse. The advertised concurrency cap does not exist.

---

## HIGH
- **H-1 — Planned secret never redacted on read and at-rest:** covered by CRIT-1; listed here for
  tracking. No encryption anywhere (`TestRun` fields are plaintext strings).
- **H-2 — Auth/cleanup URLs bypass SSRF (engine-path):** `DynamicAuthService`/`ResourceCleanupManager`
  build their own HttpClients without `SsrfProtectionGuard`; only `openapiUrl` is guarded.
- **H-3 — CORS `*` + credentials:** `config/WebConfig.java:12-17` allows any origin with
  `allowCredentials(true)`.

---

## MED
- **M-1 — Frontend hardcodes backend origin:** `frontend/src/app/new-run/page.tsx:20,37`;
  `docker-compose.yml:50` `NEXT_PUBLIC_API_URL=http://localhost:8080`. Breaks in any
  non-localhost deployment (the API is reachable at whatever host serves the UI).
- **M-2 — Weak default DB credentials baked into compose:** `docker-compose.yml:10-11` uses
  `apiqa_password` both as the Postgres password and `SPRING_DATASOURCE_PASSWORD`. `postgres`
  port `5432` is published publicly. Postgres 16 forbids `trust`; these are defaults that must be
  overridden via `.env`.
- **M-3 — OpenAPI localhost fallback:** `OpenApiParserService` falls back to
  `http://localhost:8080` when a spec has no server (safe only while SSRF on).
- **M-4 — SSE subscription lifecycle:** `SseEventService` — verify emitter cleanup on run end/partial
  reads; no ownership binding on `/events`.

---

## LOW
- **L-1 — No HTTPS enforcement at app layer** (relies on deployment termination).
- **L-2 — No request/body size limit on `POST /api/runs`** beyond DB column lengths.
- **L-3 — Logs:** dynamic token length logged (`DynamicAuthService` logs "length n") — a mild
  oracle; full payloads are not logged. Acceptable but tighten.

---

## Security regression tests needed (none exist today)
1. `GET /api/runs` and `GET /api/runs/{id}` must **not** include `authLoginPayload` (redacted).
2. Unauthenticated access to create/read must 401/403.
3. Cross-user access → 403 (IDOR).
4. DNS-rebinding/TOCTOU: same hostname resolving to public IP at validate time and private at
   connect time → still blocked (requires a resolver-proxy or connect-to-pinned-IP).
5. A `POST`/`PUT`/`DELETE` is never auto-retried; `GET` failure retried at most N with backoff.
6. `max-concurrency` actually caps concurrent runs.

## Priority order
1. CRIT-1 (encrypt or strip secrets + auth on reads) — highest.
2. CRIT-3 (Spring Security + ownership) — prerequisite for CRIT-1 fix.
3. CRIT-2 (single guarded connection client; validate+connect atomically).
4. CRIT-4 (bounded executor honoring `max-concurrency`).
