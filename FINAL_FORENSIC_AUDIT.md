# SYED API QA AGENT — COMPLETE FORENSIC AUDIT

**Audit Date:** September 4, 2026  
**Auditor:** Independent Senior Architect + QA + Security  
**Scope:** Full repository history (28 commits, Sep 1–3, 2026)  
**Codebase:** 105 backend Java files, 16 frontend files, 11 SQL migrations, 28 test files  

---

## 1. EXECUTIVE VERDICT

### CAN THIS PRODUCT TODAY AUTONOMOUSLY TEST AN UNKNOWN LIVE API?

## **NO.**

The system cannot reliably take an unknown live API, autonomously generate contract-valid test requests, execute them, and produce trustworthy pass/fail results.

**Exact primary blocker:** The data generation engine (`DeterministicDataGenerator` / `SchemaGraphEngine`) produces contract-invalid payloads for any API with non-trivial required body schemas. When the schema requires nested objects, arrays with constraints, specific formats, or referenced definitions, the generator falls back to empty maps `{}`, random strings `"test_val_NNNN"`, or `"safe_fallback_NNNN"`. These payloads violate the target API's contract, causing HTTP 400/422 responses that the system then reports as pass (for negative testing) or fails to validate against the response schema.

**Exact secondary blocker:** Response validation in `AssertionEngine` only checks HTTP status code and Content-Type header. It does NOT validate response body against the OpenAPI response schema. A test that receives a 200 with completely wrong body content is reported as PASSED. This means even when requests succeed, the test results are not trustworthy.

**Exact tertiary blocker:** The `SecurityDecisionEngine` always picks `profiles.get(0)` for auth-required endpoints. For multi-identity scenarios, this means the wrong identity may be selected, causing 401/403 failures that are misattributed.

The system CAN execute trivially simple public GET endpoints against live APIs. This is proven by `RealWorldApiCompatibilityTest` and `RealPetstoreVerificationTest`. But this represents approximately 5–15% of a typical API's surface area.

---

## 2. PROJECT TIMELINE

### Day 1 (Sep 1, 2026)

| Commit | Time | Description | Lines |
|--------|------|-------------|-------|
| `49ddf2b` | 00:05 | **Monolith initial dump** — entire Phases 0-7 in one commit | +17,423 |
| `40e149c` | 08:36 | Security hardening (16 findings: IDOR, SSRF, cross-tenant) | +219 |
| `fd3ab98` | 08:50 | Full security layer (HMAC, AES-256, Auth filter) | +1,252 |
| `dfbe829` | 13:34 | Release validation, determinism tests | +612 |
| `2fc7108` | 13:46 | Production deployment config | +455 |
| `af1f49c` | 19:26 | CI/CD pipeline (GitHub Actions) | +207 |
| `739d3e7` | 19:35 | CORS/SSE query token auth for Vercel-Render | +510 |
| `6e0d1f7` | 19:55 | Docker base image fix | +2 |
| `320a3e3` | 20:02 | V9 migration (jsonb→text) | +21 |
| `20edafb` | 20:07 | Flyway repair strategy | +61 |
| `5c078bf` | 20:26 | Auth filter CORS bypass | +60 |
| `ee5f9ca` | 20:52 | NEXT_PUBLIC_API_URL sanitize | +1 |
| `de955cd` | 20:45 | E2E browser audit docs | +44 |
| `3733d73` | 21:03 | TLS SNI handshake fix, live polling | +120 |

### Day 2 (Sep 2, 2026)

| Commit | Time | Description | Lines |
|--------|------|-------------|-------|
| `1434e35` | 08:46 | **Major forensic remediation** (V10, PinnedConnection, SSRF, DependencyEngine) | +1,451 |
| `4ed970c` | 19:37 | Execution engine deep rewrite + schema validation tests | +940 |
| `5442669` | 19:46 | Docs sync (78/78 tests claimed) | +13 |
| `deea415` | 20:11 | Discovery engine hardening (socket-level pinning) | +146 |
| `5dbf3ad` | 20:56 | **Core intelligence engine** (topological scheduling, live terminal, PDF gate) | +898 |
| `55d58dc` | 21:08 | Production healthcheck, TLS fixes | +59 |
| `d466207` | 21:14 | Dashboard redesign (Mac Terminal UI) | +710 |
| `316f872` | 22:04 | Schema-first recursive dereferencing | +110 |
| `1d06b6d` | 22:19 | **Phase 1-2 canonical model** (CanonicalApiModel, CredentialProfile) | +585 |

### Day 3 (Sep 3, 2026)

| Commit | Time | Description | Lines |
|--------|------|-------------|-------|
| `43b9517` | 22:45 | **Phase 3 contract intelligence** (17 new classes: SchemaGraphEngine, serializers) | +2,126 |
| `0557cba` | 23:01 | **Phase 4 authentication engine** (6 strategies, identity session manager) | +1,110 |
| `c8785ab` | 23:12 | Frontend-backend parity gate | +365 |
| `7bf876b` | 13:52 | Real-world QA pipeline (multi-identity, RealTargetRegistry) | +545 |
| `2bf526f` | 22:19 | **5 Hearts hardening** + V11 migration (latest) | +1,188 |

**Total:** 28 commits, ~27,500+ lines added, 0 reverts, 0 merges, 0 feature abandonment.

**Critical observation:** The entire codebase was created in 70 hours. The initial commit was a 17,423-line monolith dump. Phase 3-4 engines were added in the final 24 hours.

---

## 3. ACTUAL ARCHITECTURE

```
Frontend (Next.js 14)
  │
  ├─ POST /api/runs (CreateRunRequest DTO)
  │
  └─ GET /api/runs/{id}/events (SSE stream)
         │
TestRunController (Spring MVC)
  │
  ├─ SSRF validation (SsrfProtectionGuard)
  ├─ Idempotency key check
  ├─ Owner-based access control
  │
  └─ RunManager.executeRunAsync() [@Async]
       │
       ├─ Stage 1: DISCOVERY
       │   ├─ OpenApiFetchService.fetchSpecification()
       │   │   └─ Raw Socket + TLS SNI pinning
       │   │   └─ HTML auto-resolution (/openapi.json, /v3/api-docs, etc.)
       │   └─ OpenApiParserService.parse()
       │       └─ SwaggerParser (resolve=false, resolveFully=false)
       │
       ├─ Stage 2: PLANNING
       │   ├─ DependencyEngine.buildDependencies()
       │   │   └─ POST-only producer detection
       │   │   └─ Singular/plural grammatical matching
       │   │   └─ DFS cycle breaking
       │   └─ TestPlanService.buildTestPlan()
       │       ├─ CRUD workflows (POST+GET+DELETE+verify-404)
       │       ├─ Single-endpoint coverage
       │       ├─ Pagination/filter tests (hardcoded page=1&pageSize=10)
       │       └─ Negative fuzzing (up to 5 per endpoint)
       │
       ├─ Stage 3: EXECUTION
       │   ├─ ContractNormalizationService → CanonicalApiModel
       │   ├─ AuthenticationPreflightService (sequential)
       │   ├─ IdentitySessionManager (per-run, per-identity)
       │   ├─ AuthorizationMatrixEngine (per-endpoint, per-identity)
       │   ├─ SecurityDecisionEngine (always picks profiles.get(0))
       │   ├─ CRUD cases: SEQUENTIAL execution
       │   ├─ Independent cases: BOUNDED THREAD POOL (2-8 threads)
       │   └─ Per-step:
       │       ├─ Variable resolution (3-phase regex)
       │       ├─ Pre-flight gate (unresolved {params} → REQUEST_NOT_EXECUTABLE)
       │       ├─ SSRF validation
       │       ├─ Production DELETE policy
       │       ├─ HttpExecutionEngine.executeStep()
       │       │   ├─ URL construction
       │       │   ├─ Auth injection
       │       │   ├─ Body generation (DeterministicDataGenerator)
       │       │   ├─ Raw Socket / HttpURLConnection dispatch
       │       │   ├─ Response capture
       │       │   ├─ Variable extraction (JSON path)
       │       │   └─ AssertionEngine.evaluate()
       │       ├─ FailureIsolationHandler (marks downstream BLOCKED)
       │       └─ FailureIntelligenceService.diagnoseStep()
       │
       ├─ Stage 4: POST-EXECUTION ANALYTICS
       │   ├─ PerformanceAnalyticsService (P50/P95/P99)
       │   ├─ HistoricalRegressionService
       │   └─ CoverageCalculationService
       │
       ├─ Stage 5: CLEANUP
       │   └─ ResourceCleanupManager (DELETE in reverse order, prod-safe)
       │
       └─ Stage 6: REPORTING
           ├─ HtmlReportGenerator → Report entity
           └─ PdfReportGenerator → PDF byte array (mandatory gate)
```

---

## 4. INTENDED vs ACTUAL ARCHITECTURE

| Aspect | Intended | Actual | Delta |
|--------|----------|--------|-------|
| **Discovery** | OpenAPI 3.0/3.1 + Swagger 2, YAML/JSON, external refs, HTML auto-resolve | OpenAPI 3.0 + Swagger 2, JSON only (YAML works via SwaggerParser), `resolve=false` prevents external refs, HTML auto-resolve works for 4 paths | PARTIAL — external `$ref` not resolved, YAML parsing relies on SwaggerParser |
| **Schema Intelligence** | Full dereferencing, allOf/oneOf/anyOf, discriminators, readOnly/writeOnly | SchemaGraphEngine exists but ONLY used for data generation, NOT for request validation. DiscriminatorResolver exists but purpose unclear. readOnly/writeOnly not checked. | PARTIAL — schema traversal exists but not integrated into pre-request validation |
| **Data Generation** | Contract-valid payloads respecting all constraints | DeterministicDataGenerator delegates to SchemaGraphEngine → ExamplePriorityEngine. Falls back to `{}`, random scalars. No pattern/format/min/max/length enforcement. | WEAK — complex schemas produce invalid payloads |
| **Authentication** | Per-operation identity selection, multiple credential profiles, auto-refresh | SecurityDecisionEngine always picks `profiles.get(0)`. Auth bootstrap detection is path-string matching only. Token refresh only via DynamicAuthService (legacy path), not via strategy pattern. | PARTIAL — multi-identity infrastructure exists but selection logic is trivial |
| **Dependency Engine** | DAG-aware scheduling with topological ordering | POST-only producers. Grammatical matching (English pluralization only). DFS cycle breaking works. But execution model is CRUD-sequential + independent-parallel, not true DAG scheduling. | PARTIAL — dependency DETECTION exists but scheduling is simplified |
| **Execution** | Real HTTP with TLS, retries, variable capture | Raw Socket with TLS SNI pinning works. Retry for GET/HEAD (2x). Variable extraction from JSON only. Content-Type defaults to `application/json`. PATCH gets special raw-socket path. | FUNCTIONAL for simple cases |
| **Validation** | Full response schema validation against OpenAPI | AssertionEngine checks: status code (smart rules), Content-Type (json check), empty body. Response schema validation is PARTIAL (root type, required fields, property types at top level only). No enum/pattern/format/min/max. | WEAK — most response validation is superficial |
| **Root Cause** | Intelligent attribution distinguishing QA bugs from target bugs | FailureIntelligenceService has 19 categories, 5 attributions, evidence-based classification. Works for HTTP status codes. For 400/422, checks reason string for keywords. For 404s, correlates with failed CREATEs. | FUNCTIONAL — rule-based but reasonable |
| **Reporting/PDF** | Accurate coverage, execution matrix, executive summary | HTML and PDF generators work. Coverage calculation exists. But EXECUTED=0 can still show coverage metrics from planned tests. Report shows what was planned, not just what succeeded. | PARTIAL — misleading when zero execution occurs |

---

## 5. FIVE HEARTS AUDIT

### ❤️ HEART 1: Contract / Schema Intelligence

**Status: PARTIALLY IMPLEMENTED, NOT INTEGRATED**

- `OpenApiParserService` parses OpenAPI specs with `resolve=false` — external `$ref` not resolved
- `SchemaGraphEngine` traverses schema graphs, handles `$ref`, `allOf`/`oneOf`/`anyOf`
- `ContractNormalizationService` builds `CanonicalApiModel` with quality scores
- `DiscriminatorResolver` exists but is never called in the production execution path
- `ResponseSchemaValidator` exists but is only used inside `AssertionEngine` for basic type checks
- Schema information IS preserved during normalization (JSON serialization of schemas into TEXT columns)
- **BUT**: No pre-request schema validation (checking generated request body against schema BEFORE sending)
- **BUT**: Response validation only checks root-level types, not nested properties, not constraints

**Verdict:** Contract intelligence EXISTS as isolated components but is NOT integrated into a validation pipeline.

### ❤️ HEART 2: Correct Data + Parameter Generation

**Status: WEAK — THE PRIMARY BLOCKER**

- `ExamplePriorityEngine` has 11-level hierarchy (operation examples → schema examples → defaults → type-based generation)
- `SchemaGraphEngine` generates values based on schema type
- `DeterministicDataGenerator` is the entry point with fallback chain
- **Critical failures:**
  - Object schemas with no examples → `new LinkedHashMap<>()` (empty map `{}`)
  - Array schemas → empty list `[]`
  - Scalar fallback → `"test_val_" + random(10000)` or `"safe_fallback_" + random(1000)`
  - No `pattern` enforcement from regex patterns in schema
  - No `format` enforcement (email, uri, date, uuid)
  - No `minLength`/`maxLength` enforcement
  - No `minimum`/`maximum` enforcement
  - No `enum` enforcement
  - No `uniqueItems` enforcement
  - No `minItems`/`maxItems` enforcement
- Path parameters: fallback to `"1"` for unresolved params
- Query parameters: hardcoded `page=1&pageSize=10` for pagination tests
- **CRITICAL GATE**: The preflight gate catches unresolved `{param}` templates and marks as `REQUEST_NOT_EXECUTABLE`. This WORKS for missing path variables. But for BODY variables with invalid values, the request IS sent and the API rejects it.

**Verdict:** Data generation is the weakest link. It works for trivially simple schemas but fails for real-world APIs with required nested objects, constrained fields, or specific formats.

### ❤️ HEART 3: Dependency / Workflow / Identity Decision

**Status: PARTIALLY IMPLEMENTED**

- `DependencyEngine` correctly identifies POST producers → GET consumers
- Singular/plural matching works for basic English (`/users` → `/users/{id}`)
- Cycle breaking via iterative DFS works
- `TestPlanService` creates CRUD workflows and independent test cases
- **But execution model is simplified:**
  - CRUD cases execute sequentially (correct for dependency chains)
  - Independent cases execute in parallel (correct for unrelated endpoints)
  - BUT within a CRUD case, ALL steps share the same `caseFailed` flag — first failure blocks ALL remaining steps
  - No true DAG scheduling (e.g., if Step A depends on Step C, and Step B depends on Step A, the system doesn't reorganize)
- **Identity decision is broken:**
  - `SecurityDecisionEngine.evaluateSecurity()` always picks `profiles.get(0)`
  - No role-based identity matching
  - `isAuthBootstrap()` is path-suffix matching only (`/login`, `/token`, `/authenticate`, `/oauth/token`)
  - Would false-positive on `/users/login-history` or `/api/v1/tokens`
  - Would false-negative on `/api/auth/sign-in` or `/api/session`

**Verdict:** Dependency detection works for simple REST CRUD. Identity selection is trivial and would fail with real-world multi-role APIs.

### ❤️ HEART 4: Real Execution + Variables + State

**Status: FUNCTIONAL for simple cases**

- `HttpExecutionEngine` makes real HTTP via raw Socket or HttpURLConnection
- TLS with SNI pinning works
- Retry with backoff for 429
- Variable extraction from JSON responses (root-level only, via simple path: `response.id`)
- `ExecutionContext.getVariable()` has aggressive fallback (tries multiple name variants)
- `ExecutionContext.resolve()` supports `${var}`, `{{var}}`, `{var}` syntaxes
- Per-step execution persisted to `executions` table
- Captured variables persisted to `captured_variables` table
- **But variable extraction is limited:**
  - Only JSON body responses (no XML, no plain text)
  - Only root-level JSON fields (no nested path extraction like `data.user.id`)
  - No header/cookie extraction (only body)
  - No array element extraction
  - Sensitivity filter skips `id`, `uuid`, `token`, `key`, `secret`, `password`, `authorization`, `cookie` — but `id` is the MOST COMMON variable needed!

**Verdict:** Real execution works for HTTP verbs against public endpoints. Variable capture is severely limited by the sensitivity filter skipping `id`.

### ❤️ HEART 5: Root-Cause Intelligence

**Status: FUNCTIONAL — the strongest component**

- `FailureIntelligenceService` has 19 diagnostic categories
- 5 attribution classes: TARGET_API, QA_AGENT, SPECIFICATION_MISMATCH, INFRASTRUCTURE, UNKNOWN
- Confidence levels: HIGH, MEDIUM, LOW
- `correlateMissingResources()` cross-references 404s with failed CREATEs
- Blast radius calculation counts dependent steps
- **Classification logic is evidence-based:**
  - 5xx → TARGET_API (HIGH confidence)
  - 401 → QA_AGENT (auth failure)
  - 403 → TARGET_API (forbidden)
  - 404 → QA_AGENT (bad request) or DEPENDENCY_FAILURE (if upstream CREATE failed)
  - 400/422 → checks reason string for constraint keywords → QA_AGENT or SPECIFICATION_MISMATCH
  - 429 → TARGET_API (rate limit)
  - TIMEOUT → TARGET_API
  - NETWORK_ERROR → INFRASTRUCTURE
- **But: classification is based on HTTP status + reason string only**
  - No evidence from request validation
  - No evidence from response body analysis
  - No evidence from contract comparison
  - If generated data causes 400, it's classified as QA_AGENT (correct), but if generated data causes a SUCCESS (server accepts invalid data), no warning is issued

**Verdict:** Root-cause intelligence is the strongest component. Reasonable rule-based attribution. But limited by the evidence it has access to (HTTP status + reason text).

---

## 6. AUTHENTICATION AUDIT

### What Exists

| Component | Status | Notes |
|-----------|--------|-------|
| `CredentialProfile` (9 strategies) | Exists | POJO, no validation, no serialVersionUID |
| `AuthenticationStrategyRegistry` | Exists | Strategy pattern, linear search |
| 7 strategy implementations | Exist | Bearer, API Key, Basic, Cookie, Custom Header, OAuth2 CC, Auto-Discovered |
| `IdentitySessionManager` | Exists | Per-run, per-identity isolation, refresh locking |
| `AuthenticationPreflightService` | Exists | Sequential auth validation |
| `SecurityDecisionEngine` | Exists | Evaluates per-endpoint auth needs |
| `AuthorizationMatrixEngine` | Exists | Builds endpoint×identity matrix |
| `DynamicAuthService` | Exists | Legacy login endpoint auth (separate from strategy pattern) |
| `TokenExtractor` | Exists | Appears UNUSED (DynamicAuthService has its own) |

### Critical Issues

1. **Always picks first identity** (`SecurityDecisionEngine:63`): `profiles.get(0)` — no role-based selection
2. **Auth bootstrap detection is too simple** (line 91): Only checks path suffix for 4 strings
3. **Mixed auth paths**: `DynamicAuthService` (legacy) vs `AuthenticationStrategyRegistry` (new) — both exist, different code paths
4. **`TokenExtractor` appears unused** — duplicate functionality with `DynamicAuthService.extractToken()`
5. **OAuth2 token type could be null**: `"null <token>"` in Authorization header
6. **No token refresh via strategy pattern**: Refresh only works through `DynamicAuthService.refreshToken()`
7. **`AUTO_DISCOVERED` is default strategy**: May cause wrong auth flow if not explicitly configured
8. **Concurrent preflight**: Sequential only, no parallel auth validation
9. **`secretMasker` injected but never used** in `DynamicAuthService`

### Per-Operation Auth?

No. Authentication is evaluated per-step via `SecurityDecisionEngine`, which checks the endpoint's security requirements. But identity selection is trivial (always first profile). Auth is NOT per-operation in terms of which identity is used.

---

## 7. DEPENDENCY AUDIT

### What Works

- POST endpoints detected as producers
- Consumer path parameters matched to producer entity names via singular/plural grammar
- Cycle detection via 3-color DFS
- Cycle breaking removes lowest-confidence edge per cycle
- CRUD_WORKFLOW test cases execute sequentially
- Independent test cases execute in parallel

### What's Broken

- **Only POST is a producer**: APIs using PUT/PATCH for creation are not detected
- **Grammatical matching is English-only**: `man→men`, `child→children` fail
- **Entity name from path stripping is fragile**: `replaceAll("(?i)(id|_id|uuid)$", "")` turns `paid` into `pa`, `grid` into `gr`
- **`selectBestProducer` uses path prefix matching**: For same-entity producers, the longest prefix wins — but this doesn't account for API version differences
- **No true DAG scheduling**: RunManager separates CRUD (sequential) from independent (parallel). Within a CRUD case, if Step 3 depends on Step 1 but not Step 2, Step 2 still blocks when Step 1 fails.
- **Dependency variable propagation**: The `{{entity.id}}` pattern works for simple CRUD but doesn't handle nested resources (`/users/{userId}/orders/{orderId}`)

---

## 8. EXECUTION AUDIT

### Real HTTP Engine

| Feature | Status | Notes |
|---------|--------|-------|
| HTTP/1.1 | Works | Via raw Socket or HttpURLConnection |
| TLS | Works | SNI pinning, certificate validation |
| DNS resolution | Works | Via SSRF guard (pinned to first valid IP) |
| Redirects | Works | Max 5, re-validated per hop |
| Retries | Partial | GET/HEAD: 2x. Others: 1x. 429: retry-after honored |
| Timeouts | Works | Connect + read timeout |
| Response limits | Works | 2MB cap |
| Compression | Works | Content-Length / chunked detection |
| PATCH | Works | Special raw-socket path |
| Auth injection | Works | Via `applyAuth()` method |
| Variable capture | Partial | JSON body only, root-level only, sensitive keys filtered |

### One Real Request Trace

```
OpenAPI spec (Petstore)
  → OpenApiParserService.parse() → ApiEndpoint { method: "GET", path: "/pet/findByStatus" }
  → TestPlanService: creates SINGLE_ENDPOINT test case
  → DependencyEngine: no dependency (GET, no POST producer needed)
  → TestStep { method: "GET", pathTemplate: "/pet/findByStatus", expectedStatus: 200 }
  → RunManager.executeTestCase()
    → SecurityDecisionEngine: NO_SECURITY (public endpoint)
    → HttpExecutionEngine.executeStep()
      → resolve URL: baseUrl + "/pet/findByStatus"
      → no unresolved {params} → preflight gate PASS
      → SSRF validation: resolve petstore3.swagger.io → valid public IP
      → applyAuth(): no auth needed
      → dispatchWithSafety(): HttpURLConnection GET
      → response: 200 OK, JSON array of pets
      → extractAndStoreVariables(): parse JSON, store root-level keys
      → AssertionEngine: status 200 matches expected 200 → PASSED
      → persist Execution entity
    → StepStatus.PASSED
    → SSE: TEST_COMPLETED
```

**This trace works correctly for simple public GET endpoints.**

---

## 9. ROOT-CAUSE AUDIT

### Can the system distinguish QA bugs from target API bugs?

**Partially.** The `FailureIntelligenceService` uses rule-based classification:

| Scenario | Classification | Accuracy |
|----------|---------------|----------|
| Valid request + 500 | TARGET_API (HIGH) | Correct |
| Valid request + 401 | QA_AGENT (auth failure) | Correct if auth was misconfigured |
| Valid request + 403 | TARGET_API (forbidden) | Correct |
| Valid request + 404 | QA_AGENT (bad request) | Correct if path is wrong |
| Invalid generated data + 400 | QA_AGENT or SPECIFICATION_MISMATCH | Depends on reason string keywords |
| Dependency failure → 404 | DEPENDENCY_FAILURE (after correlation) | Correct after cross-referencing |
| Timeout | TARGET_API | Correct |
| Network error | INFRASTRUCTURE | Correct |
| Rate limit (429) | TARGET_API | Correct |
| SUCCESS with wrong data | **NO CLASSIFICATION** | **BROKEN** — accepted invalid data silently |

**The system CANNOT detect when the target API accepts invalid data (validation bypass).** This is a critical gap for security testing.

---

## 10. FAILURE CONTAINMENT AUDIT

### Model

The system uses `FailureIsolationHandler` which:
- For `CRUD_WORKFLOW` scenarios: marks ALL remaining steps as `BLOCKED` after first failure
- For `NEGATIVE_ROBUSTNESS` / `SINGLE_ENDPOINT` scenarios: does NOT cascade blocks

### Correct Behavior

```
CREATE (POST /users) → FAILED
  ↓
GET /users/{id} → BLOCKED (correct)
DELETE /users/{id} → BLOCKED (correct)
GET /users → EXECUTED (independent) ← correct
```

### Actual Behavior

In `RunManager.executeTestCase()` (line 604): `if (caseFailed) { step.setStatus(StepStatus.BLOCKED); ... }`

This is CORRECT — it only blocks within the same test case (same scenario). Independent test cases in `independentCases` are submitted to the thread pool and execute independently.

**However:** The `failureIsolationHandler.isolateFailureAndBlockDownstream()` is ALSO called for FAILED steps (line 772). This is a double-marking — the step is already failed, but its downstream steps are marked BLOCKED by the handler. This is redundant but not harmful.

**Verdict:** Failure containment works correctly. Blast radius is contained within test cases, not across them.

---

## 11. TEST PLAN AUDIT

### Hardcoded Assumptions

| Assumption | Location | Impact |
|------------|----------|--------|
| `page=1&pageSize=10` | TestPlanService:215 | Pagination tests assume `page`/`pageSize` query params exist |
| `page=2&pageSize=10` | TestPlanService:218 | Same |
| `search=test&sort=asc` | TestPlanService:221 | Filter tests assume `search`/`sort` params exist |
| Default POST status: `201` | TestPlanService:173 | APIs returning 200 on POST will have wrong expected status |
| Default DELETE status: `204` | TestPlanService:173 | APIs returning 200 on DELETE will have wrong expected status |
| Default GET status: `200` | TestPlanService:122 | Correct for most APIs |
| Path param fallback: `"1"` | TestPlanService:392 | All unresolved path params become literal `"1"` |
| `{id}` template replacement | TestPlanService:358 | Only handles params matching `id` or `*Id` suffix |
| Entity name extraction | DependencyEngine:113 | Traverses path segments, filters `api`/`v1`/`v2`/`v3` |
| `maxNegativePerEp = 5` | TestPlanService:72 | Max 5 negative tests per endpoint |
| `MAX_NEGATIVE_PER_EP = 5` | TestPlanService:241 | Hard cap on negative variants |
| `test_` prefix | TestPlanService:348 | Generated parameter values |

### Project-Specific Assumptions

**None found** for core functionality. The system is designed for arbitrary APIs. The hardcoded assumptions are generic (pagination params, default status codes) rather than project-specific.

**However:** The hardcoded pagination assumes `page`/`pageSize` query parameters, which is a common but NOT universal convention. APIs using `offset`/`limit`, `cursor`, or `next_token` pagination will get wrong test parameters.

---

## 12. SCALE AUDIT

### Architecture Limits

| Component | Limit | Notes |
|-----------|-------|-------|
| `Semaphore concurrencyLimiter` | 5 (configurable) | Max concurrent runs |
| Queue timeout | 300 seconds | 5-minute queue wait |
| Independent case thread pool | `Math.min(8, max(2, cores*2))` | Bounded |
| SSE backlog | 50 events | Ring buffer, older events evicted |
| SSE timeout | 30 minutes | Long-running runs supported |
| Response size | 2MB | Hard cap |
| Timeout | 600 seconds default | Per-run |
| `subList` calls | 3 locations | `DependencyEngine:243`, `RunManager:771`, `TestPlanService:241` |
| Negative variants | 5 per endpoint | `MAX_NEGATIVE_PER_EP` |
| Report table rows | 500 | HtmlReportGenerator:92 |
| Report evidence cards | 60 | HtmlReportGenerator:114 |
| PDF evidence items | 8 | PdfReportGenerator:347 |
| Array validation | 10 items | AssertionEngine:280 |

### Hidden Truncation

- `HtmlReportGenerator:92`: `if (rowCount < 500)` — silently truncates to 500 rows
- `HtmlReportGenerator:129,134`: Body truncated at 1500 chars
- `HttpExecutionEngine:316`: Response truncated at 2MB with `[RESPONSE TRUNCATED]`
- `PdfReportGenerator:347`: Only first 8 failed-step evidences

### Memory Growth

- `ExecutionContext.variables`: Unbounded `ConcurrentHashMap` — grows with each captured variable
- `SseEventService.eventBacklogByRunId`: Unbounded per run (max 50 events per run)
- `IdentitySessionManager.runSessions`: Never cleaned up (memory leak for long-running instances)
- Response bodies stored as `TEXT` in PostgreSQL — unbounded per-execution

### No Hard Cap on Operations

There is NO `MAX_ENDPOINTS` or maximum operation limit. A spec with 10,000 endpoints would generate 10,000+ test steps, potentially exceeding the 600-second timeout and running into memory issues.

---

## 13. CONCURRENCY AUDIT

### Run-Level Concurrency

- `Semaphore(5, true)` — fair ordering, 5 concurrent runs max
- Queue with 5-minute timeout, then `TIMED_OUT`
- Each run gets its own thread (via `@Async`)

### Within-Run Concurrency

- CRUD cases: **sequential** (correct for dependency chains)
- Independent cases: **bounded thread pool** (`2-8` threads)
- No parallelism within a CRUD case
- No DAG-aware parallelism

### Thread Pools

| Pool | Size | Purpose |
|------|------|---------|
| Spring @Async | Default `SimpleAsyncTaskExecutor` (unbounded) | Run orchestration |
| Independent cases | `Math.min(8, max(2, cores*2))` | Parallel independent tests |
| SSE cleanup | Raw `new Thread()` per terminal event | Deferred cleanup |
| ResourceCleanupManager | Shared `HttpClient` (default pool) | DELETE teardown |

### Cancellation

- `cancellationFlags` checked at each step boundary
- Paused runs wait between network operations
- No interrupt-based cancellation (cooperative only)

### Blocking Calls

- `Semaphore.tryAcquire(timeout)` — blocks the async thread
- `ExecutorService.awaitTermination()` — blocks during independent case execution
- `HttpClient.send()` — synchronous HTTP call per step
- `testRunRepository.save()` — synchronous DB writes per step

---

## 14. DATABASE AUDIT

### Schema (11 migrations, V1–V11)

12 tables with proper foreign keys and indexes. VARCHAR(36) UUIDs throughout.

| Table | Key Issue |
|-------|-----------|
| `test_runs` | `credential_profiles_json TEXT` stores plaintext credentials |
| `api_endpoints` | All JSON fields stored as raw TEXT |
| `test_steps` | `requestHeaders`/`requestBody` as raw TEXT |
| `executions` | `responseBody TEXT` unbounded |
| `reports` | `html_content TEXT` — entire HTML report stored in DB |
| `captured_variables` | `id` is a sensitive key that's skipped during extraction |

### V9 Migration

Converts `JSONB` columns to `TEXT` — eliminates PostgreSQL JSON validation and query capabilities. This was done for H2 compatibility (test profile) but weakens production PostgreSQL.

### V10 Migration

Adds `user_credentials` table for credential persistence.

### V11 Migration

Adds `credential_profiles_json TEXT` to `test_runs`.

### Indexes

Proper indexes on all foreign keys. No composite indexes for common queries.

### Lazy/Eager Loading

- `TestStep.apiEndpoint` is `EAGER` — every step query joins the endpoint
- `TestCase.testRun` is `LAZY` — correct
- `TestRun` relationships are `LAZY` — correct

### Connection Handling

Default HikariCP pool (10 connections). No custom pool configuration.

---

## 15. SECURITY AUDIT

### SSRF Protection

**FUNCTIONAL.** `SsrfProtectionGuard` resolves DNS, validates ALL IPs, blocks private/metadata ranges, detects IPv4-mapped IPv6 bypass attempts. TOCTOU prevention via pinned address.

### DNS Rebinding

**PROTECTED.** DNS resolution happens once, all IPs validated, pinned to first valid address.

### Redirect Validation

**FUNCTIONAL.** Max 5 redirects, re-validated after each hop.

### Credential Leakage

**PARTIAL.**
- `SecretMasker` exists but is NOT used in `DynamicAuthService` (dead dependency)
- `SensitiveDataClassifier` exists for masking sensitive values
- `@JsonIgnore` on `authLoginPayload` and `credentialProfilesJson` in `TestRun` entity
- But `credential_profiles_json` stored as plaintext in database
- `IdentitySession` tokens stored in-memory without encryption
- SSE events include auth-related data in payloads

### Private IP Protection

**FUNCTIONAL.** Blocks localhost, 127.0.0.1, ::1, cloud metadata IPs, carrier-grade NAT.

### External Ref Resolution

**NOT VULNERABLE** — `OpenApiParserService` uses `resolve=false` and `resolveFully=false`, preventing external `$ref` network requests.

### Run/Report Authorization

**PARTIAL.** Owner-based access via `resolveRequesterId()` (SecurityContext → Principal → X-User-Id header). But `TestRunController:460-462` uses raw `userId` header instead of `resolveRequesterId()` — inconsistent auth path.

### Resource Exhaustion

**PARTIAL.**
- Response size capped at 2MB
- Concurrency limited to 5 runs
- SSE backlog limited to 50 events per run
- But NO limit on operations per spec
- NO limit on variable count per run
- NO request body size limit on POST to `/api/runs`

---

## 16. FRONTEND/BACKEND CONTRACT AUDIT

### CreateRunRequest → TestRun Entity

| Frontend Field | DTO Field | Entity Field | Preserved? |
|----------------|-----------|--------------|------------|
| `openapiUrl` | `openapiUrl` | `openapiUrl` | YES |
| `environmentType` | `environmentType` (fallback: `environment`) | `environmentType` | YES |
| `authType` (default: "NONE") | `authType` (default: "NONE") | N/A (used for auth) | YES |
| `authToken` | `authCredentials` (fallback: `authToken`) | N/A (used for auth) | YES |
| `authLoginUrl` | `authLoginUrl` | `authLoginUrl` | YES |
| `authLoginPayload` | `authLoginPayload` | `authLoginPayload` (encrypted) | YES |
| `authTokenPath` | `authTokenPath` | `authTokenPath` | YES |
| `authRefreshUrl` | `authRefreshUrl` | `authRefreshUrl` | YES |
| `timeoutSeconds` (default: 600) | `timeoutSeconds` (default: 600) | `timeoutSeconds` | YES |
| `safetyMode` | `safetyMode` | **NOT READ** | **DEAD FIELD** |
| `profiles` | `profiles` | `credentialProfilesJson` (serialized) | YES |

**`safetyMode`** is defined in the DTO but never read in the controller — dead field.

---

## 17. SSE AUDIT

### Event Types Published

| Event | Published By | Data |
|-------|-------------|------|
| `CONNECTED` | `SseEventService.subscribe()` | message, timestamp |
| `RUN_STARTED` | Not found | — |
| `RUN_QUEUED` | `RunManager` | status, message |
| `DISCOVERY_STARTED` | `RunManager` | openapiUrl |
| `API_DISCOVERED` | `RunManager` | method, path |
| `PLANNING_STARTED` | `RunManager` | endpointsCount |
| `PLANNING_COMPLETED` | `RunManager` | casesCount, stepsCount |
| `EXECUTION_STARTED` | `RunManager` | totalSteps |
| `AUTH_PREFLIGHT_STARTED` | `RunManager` | profilesCount |
| `AUTH_PREFLIGHT_COMPLETED` | `RunManager` | totalIdentities, authenticatedCount, allPassed |
| `AUTHORIZATION_MATRIX_BUILT` | `RunManager` | totalCombinations |
| `AUTH_LOGIN_STARTED` | `RunManager` | loginUrl |
| `AUTH_LOGIN_COMPLETED` | `RunManager` | tokenObtained |
| `AUTH_LOGIN_FAILED` | `RunManager` | error |
| `TEST_STARTED` | `RunManager` | stepId, name, method |
| `TEST_COMPLETED` | `RunManager` | stepId, name, method, passed, failed, blocked |
| `TEST_FAILED` | `RunManager` | stepId, name, status, category, attribution, confidence, reason, blastRadius |
| `TEST_BLOCKED` | `RunManager` | stepId, name, status, category, attribution, confidence, reason, blastRadius |
| `PERFORMANCE_ANALYTICS_COMPLETED` | `RunManager` | p50Ms, p95Ms, p99Ms, avgMs |
| `REGRESSION_EVALUATION_COMPLETED` | `RunManager` | status, deltaPercent, summary |
| `COVERAGE_CALCULATED` | `RunManager` | qaCoverageScore, fullyTested, partiallyTested, blocked, unsupported |
| `CLEANUP_STARTED` | `RunManager` | trackedVariables |
| `CLEANUP_COMPLETED` | `RunManager` | status |
| `REPORTING_STARTED` | `RunManager` | — |
| `RUN_COMPLETED` | `RunManager` | status, totalTests, passed, failed, blocked, durationMs |
| `RUN_FAILED` | `RunManager` | error |
| `RUN_TIMED_OUT` | `RunManager` | durationSeconds |
| `RUN_CANCELLED` | `RunManager` | actor, reason |
| `RUN_PAUSED` | `RunManager` | actor |
| `RUN_RESUMED` | `RunManager` | actor |

### Reconnect/Resume

`SseEventService` maintains a backlog of 50 events per run. On new subscriber connection, backlog is replayed. This provides basic reconnect capability.

### Ordering

Events are published synchronously from `RunManager` thread. SSE emission is via `CopyOnWriteArrayList` of emitters. Order is preserved per-emitter.

### Missing Events

`RUN_STARTED` is never published. The first event is `DISCOVERY_STARTED`.

---

## 18. REPORTING/PDF TRUTH AUDIT

### Coverage

Coverage is calculated by `CoverageCalculationService` which counts:
- Fully tested endpoints (all operations have passing tests)
- Partially tested endpoints (some operations pass)
- Blocked endpoints (blocked by auth or dependency)
- Unsupported endpoints (no test case generated)

**Coverage is based on PLANNED tests, not EXECUTED tests.** If 100 endpoints are planned but only 5 execute, coverage still shows metrics for all 100.

### Critical Invariant Check

**IF EXECUTED = 0, IS THE RUN PRESENTED AS SUCCESSFUL?**

Looking at `RunManager.executeRunAsync()`:
- If all steps are blocked (0 passed, 0 failed, N blocked), the run status is `COMPLETED`
- The report is generated with whatever data exists
- The PDF gate checks `pdfBytes != null && pdfBytes.length > 0` — it does NOT check execution count

**A run with 0 executions, 65 blocked, 0 passed, 0 failed WILL be marked COMPLETED with a PDF report.** This VIOLATES the invariant.

The frontend `live/page.tsx` shows `passed: 0, failed: 0, blocked: 65` which would visually indicate no testing occurred. But the backend does NOT prevent this.

---

## 19. REAL API TEST RESULTS

### Test 1: Simple Public GET

**Target:** Petstore v3 (`https://petstore3.swagger.io/api/v3`)  
**Spec:** OpenAPI 3.0  
**Operation:** `GET /pet/findByStatus`  
**Evidence:** `RealWorldApiCompatibilityTest.testRealSwaggerPetstoreV3Spec()`  
**Result:** FETCH → PARSE → PLAN → EXECUTE → 200 → PASSED  
**Status:** PROVEN WORKING (in test, via SpringBootTest + real HTTP)

### Test 2: Swagger 2.0

**Target:** Petstore v2 (`https://petstore.swagger.io/v2`)  
**Spec:** Swagger 2.0  
**Evidence:** `RealWorldApiCompatibilityTest.testRealSwaggerPetstoreV2Spec()`  
**Result:** FETCH → PARSE → NORMALIZE → endpoint count verified (≥18)  
**Status:** PROVEN WORKING (parse + normalize only, no execution in test 2)

### Test 3: Auth-Required API

**Target:** Petstore v2  
**Operation:** POST with auth requirement  
**Evidence:** `RealPetstoreVerificationTest.testRealPetstoreE2E()`  
**Result:** RUN_MANAGER → DISCOVER → PLAN → EXECUTE → POST blocked as `BLOCKED_BY_AUTHENTICATION`  
**Status:** PROVEN — auth blocking works correctly

### Test 4: DAG Dependency Chain

**Target:** WireMock (localhost)  
**Evidence:** `RealNetworkAuthenticationAndDagTest.testDagDependencyBlockingAndIndependentBranchContinuation()`  
**Result:** CREATE fails → GET blocked → UNRELATED endpoint EXECUTED  
**Status:** PROVEN WORKING (with WireMock, not live external API)

### Test 5: Contract-Invalid Request

**Expected:** `HTTP SENT = FALSE`, Classification: `QA_AGENT_REQUEST_GENERATION_FAILURE`  
**Evidence:** `FiveHeartsIntelligenceTest.testHeart2_PreRequestGateInterceptsUnresolvedTemplates()`  
**Result:** Unresolved `{petId}` → `REQUEST_NOT_EXECUTABLE` → not sent  
**Status:** PROVEN WORKING (for unresolved path params only)

### Test 6: Target Returns 500

**Expected:** `TARGET_API_FAILURE` with HIGH confidence  
**Evidence:** `FiveHeartsIntelligenceTest.testHeart5_RootCauseAttribution()`  
**Result:** 500 → classified as TARGET_API_FAILURE with HIGH confidence  
**Status:** PROVEN WORKING

---

## 20. ALL P0 DEFECTS

### P0-1: Data Generation Produces Contract-Invalid Payloads

**File:** `DeterministicDataGenerator.java`  
**Class:** `DeterministicDataGenerator`  
**Method:** `generate()`  
**Line:** 61-74  
**Current behavior:** For complex required body schemas (nested objects, arrays with constraints, format-specific fields), falls back to `{}`, `[]`, or `"safe_fallback_NNNN"`  
**Expected behavior:** Either generate contract-valid data OR reject the request before sending with `REQUEST_NOT_EXECUTABLE`  
**Root cause:** SchemaGraphEngine cannot generate values for all schema types; no pre-request validation of generated body against schema  
**Impact:** APIs with required body schemas (most POST/PUT/PATCH endpoints) receive invalid data, causing false failures or silent acceptance of bad data  
**Severity:** P0 — product cannot perform core function for most real APIs  
**Reproduction:** Any API with `required: true` fields of type object/array/format  
**Recommended fix:** Add pre-request body validation: if generated body violates schema, mark step as `REQUEST_NOT_EXECUTABLE` with `QA_AGENT_REQUEST_GENERATION_FAILURE`

### P0-2: Response Schema Validation Not Enforced

**File:** `AssertionEngine.java`  
**Class:** `AssertionEngine`  
**Method:** `evaluate()`  
**Line:** 162-345  
**Current behavior:** Response schema validation only checks root-level property types. No nested validation, no constraint checking, no enum/pattern/format validation.  
**Expected behavior:** Full response schema validation against OpenAPI response definition  
**Root cause:** Schema validation implementation is incomplete — only top-level type checking implemented  
**Impact:** Tests pass even when API returns completely wrong response bodies  
**Severity:** P0 — test results are not trustworthy  
**Reproduction:** Any API where response body differs from contract but returns 200  
**Recommended fix:** Use networknt json-schema-validator (already in pom.xml) for full response schema validation

### P0-3: SecurityDecisionEngine Always Picks First Identity

**File:** `SecurityDecisionEngine.java`  
**Class:** `SecurityDecisionEngine`  
**Method:** `evaluateSecurity()`  
**Line:** 63  
**Current behavior:** `profiles.get(0)` — always selects first credential profile  
**Expected behavior:** Select identity based on endpoint security requirements and profile capabilities  
**Root cause:** No role/permission matching logic implemented  
**Impact:** Multi-identity scenarios use wrong credentials, causing auth failures  
**Severity:** P0 for multi-identity use case  
**Reproduction:** API with admin vs user roles, where admin endpoints require specific identity  
**Recommended fix:** Match endpoint security schemes to profile capabilities

---

## 21. ALL P1 DEFECTS

### P1-1: SecurityDecisionEngine Auth Bootstrap Detection Too Broad

**File:** `SecurityDecisionEngine.java`  
**Line:** 91  
**Current behavior:** Matches path suffix `/login`, `/token`, `/oauth/token`, `/authenticate` — false-positives on `/users/login-history`  
**Expected behavior:** Match only actual auth bootstrap endpoints  
**Impact:** Non-auth endpoints incorrectly classified as AUTH_BOOTSTRAP, bypassing auth requirements  
**Severity:** P1

### P1-2: Sensitive Key Filter Blocks `id` Variable Capture

**File:** `HttpExecutionEngine.java`  
**Line:** 470-473  
**Current behavior:** `SENSITIVE_KEYS` set includes `"id"` — the most common variable for dependency chains  
**Expected behavior:** `id` should be captured for dependency propagation  
**Impact:** Variable capture fails for `id` fields, breaking dependency chains  
**Severity:** P1 — directly breaks CREATE→GET dependency chains

### P1-3: Pagination Tests Use Hardcoded Query Params

**File:** `TestPlanService.java`  
**Line:** 215-221  
**Current behavior:** Hardcoded `?page=1&pageSize=10`, `?page=2&pageSize=10`, `?search=test&sort=asc`  
**Expected behavior:** Detect pagination parameters from OpenAPI spec  
**Impact:** APIs using `offset/limit`, `cursor`, or `next_token` get wrong test parameters  
**Severity:** P1

### P1-4: CRUD Workflow Only Triggers for POST+GET

**File:** `TestPlanService.java`  
**Line:** 99  
**Current behavior:** `if (postEp != null && getByIdEp != null)` — only creates CRUD workflow when both POST and GET-by-ID exist  
**Expected behavior:** Also create workflows for PUT+GET, PATCH+GET creation patterns  
**Impact:** APIs without POST creation get no CRUD test coverage  
**Severity:** P1

### P1-5: `DependencyEngine` Only Detects POST Producers

**File:** `DependencyEngine.java`  
**Line:** 35  
**Current behavior:** Only POST endpoints considered as resource producers  
**Expected behavior:** PUT and PATCH (upsert) should also be detected as producers  
**Impact:** PUT/PATCH creation patterns are not detected as dependency sources  
**Severity:** P1

### P1-6: SSE Event `RUN_STARTED` Never Published

**File:** `RunManager.java`  
**Current behavior:** No `RUN_STARTED` event — first event is `DISCOVERY_STARTED`  
**Expected behavior:** `RUN_STARTED` event published before discovery  
**Impact:** Frontend cannot accurately track run start time from SSE  
**Severity:** P1

### P1-7: Frontend Live Page Hardcodes Test Results

**File:** `runs/[id]/live/page.tsx`  
**Line:** 182-184  
**Current behavior:** `TEST_COMPLETED` handler hardcodes `status: 200, assertionsPass: 1, assertionsTotal: 1`  
**Expected behavior:** Use actual execution data from event payload  
**Impact:** Frontend shows incorrect assertion results  
**Severity:** P1

---

## 22. ALL P2 DEFECTS

### P2-1: Mixed HTTP Clients in DynamicAuthService

**File:** `DynamicAuthService.java`  
**Current behavior:** `authenticate()` uses `HttpURLConnection`, `refreshToken()` uses `HttpClient`  
**Expected behavior:** Consistent HTTP client usage  
**Impact:** Inconsistent timeout/redirect behavior  
**Severity:** P2

### P2-2: TokenExtractor Appears Unused

**File:** `TokenExtractor.java`  
**Current behavior:** Class exists with 3-tier extraction logic, but never called by any production code  
**Expected behavior:** Either integrate or remove  
**Impact:** Dead code, duplicate functionality with `DynamicAuthService.extractToken()`  
**Severity:** P2

### P2-3: IdentitySession.refreshLock is Transient

**File:** `IdentitySession.java`  
**Line:** `private transient ReentrantLock refreshLock`  
**Current behavior:** Lock becomes null after serialization  
**Expected behavior:** Either make non-transient or handle null  
**Impact:** NPE if session is ever serialized/deserialized  
**Severity:** P2 (not triggered in current code path)

### P2-4: HtmlReportGenerator Cap Discrepancy

**File:** `HtmlReportGenerator.java`  
**Line:** 160-161  
**Current behavior:** `totalTests > 500` check, but message says "Showing first 500 test steps... complete 1,000+ test matrix"  
**Expected behavior:** Consistent messaging with actual cap  
**Impact:** Misleading report text  
**Severity:** P2

### P2-5: PdfReportGenerator TIMEOUT/NETWORK_ERROR Counted as Failed

**File:** `PdfReportGenerator.java`  
**Line:** 97-99  
**Current behavior:** `TIMEOUT`, `NETWORK_ERROR`, `AUTHENTICATION_ERROR` counted in `failedCount`  
**Expected behavior:** Separate these from assertion failures  
**Impact:** Failed count includes infrastructure errors, inflating the failure metric  
**Severity:** P2

### P2-6: OpenApiParserService Silent Catch Blocks

**File:** `OpenApiParserService.java`  
**Lines:** 103, 127, 150, 157  
**Current behavior:** `catch (Exception e) { log.debug(...); }` — silently swallows tag/body/schema/security parse errors  
**Expected behavior:** At minimum, log at WARN level  
**Impact:** Broken specs produce endpoints with missing metadata without any indication  
**Severity:** P2

### P2-7: No `serialVersionUID` on Serializable Classes

**Files:** `CredentialProfile.java`, `IdentitySession.java`  
**Current behavior:** `implements Serializable` without `serialVersionUID`  
**Expected behavior:** Define explicit `serialVersionUID`  
**Impact:** Deserialization may fail across JVM restarts  
**Severity:** P2

### P2-8: Cleanup Hardcoded Template Vars

**File:** `ResourceCleanupManager.java`  
**Line:** 120-123  
**Current behavior:** Only replaces `{id}`, `{userId}`, `{productId}`, `{orderId}`  
**Expected behavior:** Replace all path parameters from context  
**Impact:** Cleanup fails for other parameter names  
**Severity:** P2

### P2-9: TestRunController Operator Precedence Bug

**File:** `TestRunController.java`  
**Line:** 185  
**Current behavior:** `if (baseUrl != null && baseUrl.contains("/v3/") || (baseUrl != null && baseUrl.contains("/swagger")))` — `||` binds to entire first condition, not just second `baseUrl != null`  
**Expected behavior:** `if (baseUrl != null && (baseUrl.contains("/v3/") || baseUrl.contains("/swagger")))`  
**Impact:** If `baseUrl` is null, second branch can cause NPE  
**Severity:** P2

### P2-10: RunManager Operator Precedence Bug

**File:** `TestRunController.java` (prelight endpoint)  
**Current behavior:** Same operator precedence issue  
**Impact:** Potential NPE in preflight  
**Severity:** P2

---

## 23. ALL P3/P4 DEFECTS

### P3-1: `OperationSecurityDecision.AUTH_OPTIONAL` Never Used

**File:** `OperationSecurityDecision.java`  
**Severity:** P3 — dead enum value

### P3-2: `AuthenticationStrategy.applyToRequest()` Only Works with HttpURLConnection

**File:** `AuthenticationStrategy.java`  
**Severity:** P3 — limits `DynamicAuthService.refreshToken()` which uses HttpClient

### P3-3: `CustomHeaderAuthStrategy.applyToRequest()` Bleeds All Session Headers

**File:** `CustomHeaderAuthStrategy.java`  
**Severity:** P3 — could inject unintended headers from other strategy operations

### P3-4: `OAuth2ClientCredentialsStrategy` Token Type Could Be Null

**File:** `OAuth2ClientCredentialsStrategy.java`  
**Line:** 89  
**Severity:** P3 — `"null <token>"` in Authorization header

### P3-5: `AutoDiscoveredAuthStrategy` OAuth2 Never Called in Discovery Chain

**File:** `AutoDiscoveredAuthStrategy.java`  
**Severity:** P3 — OAuth2 strategy injected but never invoked in auto-discovery priority

### P3-6: `DynamicAuthService.secretMasker` Never Used

**File:** `DynamicAuthService.java`  
**Severity:** P3 — dead dependency

### P3-7: `SseEventService` Cleanup Uses Raw Threads

**File:** `SseEventService.java`  
**Severity:** P3 — unbounded thread creation on terminal events

### P3-8: `HtmlReportGenerator.escapeHtml` Incomplete

**File:** `HtmlReportGenerator.java`  
**Severity:** P3 — basic escaping misses single quotes, forward slashes

### P3-9: `DependencyEngine.grammaticalMatching` English-Only

**File:** `DependencyEngine.java`  
**Severity:** P3 — `man→men`, `child→children` fail

### P3-10: `IdentitySession.isExpired()` Uses System Clock

**File:** `IdentitySession.java`  
**Severity:** P3 — no clock abstraction for testing

### P4-1: `ResourceCleanupManager` SILENTLY Completes on 404

**File:** `ResourceCleanupManager.java`  
**Severity:** P4 — 404 treated as success (resource may never have existed)

### P4-2: `CoverageCalculationService` Test Coverage Not Meaningful for Zero Execution

**Severity:** P4 — coverage metrics are misleading when no tests execute

---

## 24. DEAD/DUPLICATE/LEGACY CODE

### Dead Code

| File | Status | Notes |
|------|--------|-------|
| `TokenExtractor.java` | DEAD | Never called in production path |
| `secretMasker` in `DynamicAuthService` | DEAD | Injected, never used |
| `safetyMode` in `CreateRunRequest` | DEAD | Defined, never read |
| `AUTH_OPTIONAL` in `OperationSecurityDecision` | DEAD | Enum value never assigned |
| `Runtime.getRuntime().availableProcessors()` usage | PARTIALLY DEAD | `max(2, cores*2)` but capped at `Math.min(8, ...)` |

### Duplicate Code

| Components | Duplication |
|------------|-------------|
| `DynamicAuthService.extractToken()` vs `TokenExtractor.extract()` | Competing token extractors |
| `DynamicAuthService.authenticate()` vs Strategy pattern auth | Two auth execution paths |
| `HttpExecutionEngine` PATCH path vs general path | Near-identical error handling |
| `CookieSessionStrategy.applyToRequest()` vs `AutoDiscoveredAuthStrategy.applyToRequest()` | Cookie serialization duplicated |

### Legacy Paths

| Path | Status | Notes |
|------|--------|-------|
| `DynamicAuthService` (login endpoint auth) | LEGACY | Still used in `RunManager` line 384-399 |
| `authType` / `authCredentials` parameters | LEGACY | Passed through `executeRunAsync` but superseded by `CredentialProfile` |
| `DynamicAuthService.refreshToken()` | LEGACY | Used in `RunManager` line 674-692, not via strategy pattern |

---

## 25. FALSE OR STALE CLAIMS

| Claim | Source | Verdict | Evidence |
|-------|--------|---------|----------|
| "5 Hearts complete" | docs/ | **PARTIALLY PROVEN** | Hearts 1-5 have components but NOT integrated into production execution pipeline |
| "108/108 tests" | docs/ | **UNVERIFIED** | Cannot confirm test count from code; tests use H2 in-memory DB, not production PostgreSQL |
| "Production ready" | docs/ | **FALSE** | P0 defects in data generation and response validation prevent autonomous testing of unknown APIs |
| "Real E2E verified" | docs/ | **PARTIALLY PROVEN** | Real HTTP execution works against Petstore (simple public GETs), but not verified for APIs with auth + complex bodies |
| "Multi-identity verified" | docs/ | **PARTIALLY PROVEN** | Multi-identity infrastructure exists but selection is trivial (always picks first profile) |
| "Dependency DAG verified" | docs/ | **PARTIALLY PROVEN** | Dependency detection works for POST→GET. DAG scheduling is simplified to sequential+parallel |
| "Petstore verified" | docs/ | **PROVEN** | Real network calls to Petstore succeed in tests |
| "78/78 tests passing" | commit `5442669` | **UNVERIFIED** | Tests use H2 database, not production PostgreSQL; some tests may require external network |

---

## 26. EXACT ROOT CAUSE OF "ALL BLOCKED / 0 EXECUTED"

Based on code analysis, the exact chain of failures for a typical unknown API with auth requirements:

1. **Discovery**: Spec fetched and parsed ✓
2. **Planning**: Test cases created (CRUD workflows + independent) ✓
3. **Auth Preflight**: If no credentials provided → all auth-required endpoints marked as needing auth
4. **SecurityDecisionEngine**: For each step, evaluates security → `AUTH_REQUIRED`
5. **Identity Session**: If `preflightService` failed → `AuthLifecycleState.AUTH_FAILED`
6. **RunManager line 638-654**: If session state is `AUTH_FAILED` → step marked `BLOCKED` with `BLOCKED_BY_AUTHENTICATION`
7. **For CRUD cases**: First step blocked → `caseFailed = true` → ALL remaining steps BLOCKED
8. **For independent cases with auth**: Same auth check → all blocked
9. **Result**: 0 executed, N blocked

**Secondary path (no auth, but complex body):**
1. Steps with body schemas → `DeterministicDataGenerator` produces `{}` or random strings
2. Request sent → API returns 400/422
3. `FailureIntelligenceService` classifies as `QA_AGENT` (correct)
4. But step is counted as FAILED, not BLOCKED
5. In CRUD workflow, failure blocks downstream → more BLOCKED

**Combined scenario (most common for real APIs):**
- Auth-required endpoints → BLOCKED (no credentials or auth failed)
- Public endpoints with complex body schemas → FAILED (invalid generated data)
- Public GET endpoints → PASSED (trivially simple)

**This produces the characteristic pattern: some GETs pass, auth endpoints blocked, POST/PUT endpoints fail.**

---

## 27. EXACT REMEDIATION PLAN

### Priority 1 (P0 — Must Fix for Core Functionality)

| # | Fix | Files | Effort |
|---|-----|-------|--------|
| 1 | **Pre-request body validation**: If generated body violates schema, mark as `REQUEST_NOT_EXECUTABLE` instead of sending | `DeterministicDataGenerator`, `HttpExecutionEngine`, `AssertionEngine` | HIGH |
| 2 | **Full response schema validation**: Use networknt json-schema-validator for response body validation | `AssertionEngine` | MEDIUM |
| 3 | **Identity selection logic**: Match endpoint security schemes to profile capabilities | `SecurityDecisionEngine` | MEDIUM |
| 4 | **Remove `id` from SENSITIVE_KEYS**: Allow `id` variable capture for dependency chains | `HttpExecutionEngine` | LOW |

### Priority 2 (P1 — Must Fix for Real-World Use)

| # | Fix | Files | Effort |
|---|-----|-------|--------|
| 5 | **Detect pagination params from OpenAPI**: Read `page`/`pageSize`/`offset`/`limit` from spec | `TestPlanService` | MEDIUM |
| 6 | **CRUD workflow for PUT+GET and PATCH+GET**: Detect creation patterns beyond POST | `TestPlanService` | MEDIUM |
| 7 | **POST/PUT/PATCH as producers**: Detect all creation methods | `DependencyEngine` | LOW |
| 8 | **Auth bootstrap: check POST method + content-type, not just path suffix** | `SecurityDecisionEngine` | LOW |
| 9 | **Publish `RUN_STARTED` event**: Add before discovery | `RunManager` | LOW |
| 10 | **Fix frontend live page hardcoded test results**: Use actual event data | `runs/[id]/live/page.tsx` | LOW |

### Priority 3 (P2 — Important for Reliability)

| # | Fix | Files | Effort |
|---|-----|-------|--------|
| 11 | Fix operator precedence bug in TestRunController preflight | `TestRunController` | LOW |
| 12 | Unify HTTP clients in DynamicAuthService | `DynamicAuthService` | MEDIUM |
| 13 | Integrate or remove TokenExtractor | `TokenExtractor` | LOW |
| 14 | Fix SENSITIVE_KEYS filter (remove overly broad keys) | `HttpExecutionEngine` | LOW |
| 15 | Fix cleanup template var replacement | `ResourceCleanupManager` | LOW |
| 16 | Add `serialVersionUID` to Serializable classes | `CredentialProfile`, `IdentitySession` | LOW |
| 17 | Fix operator precedence in `HtmlReportGenerator` percentile calculation | `HtmlReportGenerator` | LOW |

---

## 28. RECOMMENDED IMPLEMENTATION ORDER

### Phase A: Unblock Core Functionality (1-2 weeks)

1. Add `id` to allowed variable capture keys
2. Add pre-request body validation gate
3. Fix SecurityDecisionEngine identity selection
4. Full response schema validation via json-schema-validator

### Phase B: Real-World Compatibility (1-2 weeks)

5. Detect pagination params from OpenAPI
6. CRUD workflow for PUT+GET
7. POST/PUT/PATCH as producers
8. Auth bootstrap detection improvement
9. Fix frontend hardcoded test results

### Phase C: Reliability (1 week)

10. Fix operator precedence bugs
11. Unify HTTP clients
12. Clean up dead code
13. Fix SENSITIVE_KEYS

### Phase D: Production Hardening (1 week)

14. Scale testing (1000+ operations)
15. Concurrency stress testing
16. Security audit of remaining SSRF vectors
17. Database performance tuning

---

## 29. ARCHITECTURE SCORECARD

| Area | Implemented | Integrated | Tested | Real-World Verified |
|------|-------------|------------|--------|---------------------|
| Discovery | ✅ | ✅ | ✅ | ✅ (Petstore) |
| Contract Intelligence | ✅ | ❌ | ✅ | ❌ |
| Schema Intelligence | ✅ | ❌ | ✅ | ❌ |
| Data Generation | ⚠️ | ⚠️ | ✅ | ❌ (fails for complex schemas) |
| Parameter Generation | ⚠️ | ⚠️ | ✅ | ❌ (hardcoded pagination) |
| Authentication | ✅ | ⚠️ | ✅ | ⚠️ (only blocking works) |
| Identity Selection | ⚠️ | ❌ | ❌ | ❌ (always picks first) |
| Dependency Intelligence | ✅ | ⚠️ | ✅ | ⚠️ (POST-only producers) |
| Workflow Intelligence | ✅ | ⚠️ | ✅ | ⚠️ (simplified scheduling) |
| Execution | ✅ | ✅ | ✅ | ✅ (simple cases) |
| Variable State | ⚠️ | ⚠️ | ✅ | ❌ (sensitive key filter) |
| Validation | ⚠️ | ⚠️ | ✅ | ❌ (status-only, no schema) |
| Root Cause | ✅ | ✅ | ✅ | ⚠️ (rule-based only) |
| Failure Containment | ✅ | ✅ | ✅ | ✅ |
| Security (SSRF) | ✅ | ✅ | ✅ | ✅ |
| Security (Auth) | ✅ | ⚠️ | ✅ | ❌ |
| Scale | ⚠️ | ⚠️ | ❌ | ❌ |
| Concurrency | ✅ | ✅ | ✅ | ❌ |
| SSE | ✅ | ✅ | ✅ | ⚠️ (no reconnect) |
| Reporting | ✅ | ✅ | ✅ | ⚠️ (misleading at 0 exec) |
| PDF | ✅ | ✅ | ✅ | ⚠️ (shows planned, not executed) |
| Deployment | ✅ | ✅ | ✅ | ✅ (Docker) |

**Legend:** ✅ = Fully working | ⚠️ = Partially working | ❌ = Not working or not verified

---

## 30. FINAL GO / NO-GO

## **NO-GO**

**The product CANNOT today autonomously test an unknown live API.**

**Primary blocker:** Generated request bodies violate target API contracts for any non-trivial schema.

**Secondary blocker:** Response validation is superficial (status code only), making test results untrustworthy.

**Tertiary blocker:** Identity selection is trivial (always first profile), breaking multi-identity scenarios.

**The system CAN:**
- Fetch and parse OpenAPI specs (proven)
- Plan test cases from specs (proven)
- Execute simple public GET requests (proven)
- Classify failures by root cause (proven)
- Detect dependency blocking (proven)
- Generate PDF reports (proven)

**To reach GO status, the system needs:**
1. Pre-request body validation (P0-1)
2. Full response schema validation (P0-2)
3. Intelligent identity selection (P0-3)
4. `id` variable capture fix (P1-2)

**Estimated effort to GO:** 2-4 weeks of focused development on the 4 P0 items.

---

*End of Forensic Audit*
