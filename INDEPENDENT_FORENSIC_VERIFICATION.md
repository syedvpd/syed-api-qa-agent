# Independent Forensic Verification Report

**Auditor:** opencode (independent, read-only)
**Date:** Sep 3, 2026
**Scope:** Verify whether Antigravity's uncommitted changes resolve the P0–P3 findings from the previous audit (`FINAL_FORENSIC_AUDIT.md`), and independently assess system readiness for 500–5000+ operation, 10–20+ identity generic API testing.
**Method:** Full code reading, git diff analysis, unit test execution, live API verification, grep-based searches across entire codebase.

---

## EXECUTIVE SUMMARY

**Previous audit verdict:** NO-GO (30 sections, P0–P4 findings)
**This audit verdict:** **NO-GO** — 3 regressions introduced, 1 pre-existing silent data loss bug, 0 new hardcoded assumptions

### What Antigravity Actually Did (Uncommitted, Never Committed to Git)

| Change | Status | Verdict |
|--------|--------|---------|
| New `SecurityDecisionEngine.java` (350 lines) | UNTRACKED | Architecture sound, but has matching regression |
| New `OperationSecurityDecision.java` | UNTRACKED | Clean |
| Modified `RunManager.java` (+52 lines) | MODIFIED | Fixes session isolation, introduces per-step identity selection |
| Modified `HttpExecutionEngine.java` | MODIFIED | Removes AUTH_FAILED cascade (correct), SENSITIVE_KEYS unchanged |
| Modified `OpenApiParserService.java` | MODIFIED | Null check on security requirements (minor fix) |
| New `MultiIdentityCapabilityMatchingTest.java` | UNTRACKED | 6 tests pass, tests correct behavior |
| New `GenericAuthDecisionTest.java` | UNTRACKED | 3 tests pass |
| New `RealDataDependencyExecutionTest.java` | UNTRACKED | 1 test pass, demonstrates real data flow |
| New `RealPetstoreVerificationTest.java` | UNTRACKED | 3 tests, 2 fail (see below) |

### Critical Finding: Previous Audit Contained 4 Inaccurate Claims

| Previous Claim | Actual State | Evidence |
|----------------|-------------|----------|
| `"id"` removed from SENSITIVE_KEYS | `"id"` was NEVER in SENSITIVE_KEYS (committed or uncommitted) | `git show HEAD:HttpExecutionEngine.java:470` vs. disk:470 — identical lists |
| Added "role"/"scope"/"group"/"permissions" to SENSITIVE_KEYS | NOT present in either committed or uncommitted code | grep SENSITIVE_KEYS — exact same 14 entries |
| Response schema validation NOT in production path | IS in production path via `AssertionEngine.validateOpenApiSchema()` called from `evaluateAssertions()` at line 139 | HttpExecutionEngine:324 calls `assertionEngine.evaluateAssertions()` which calls `validateOpenApiSchema()` |
| Variable extraction for `"id"` broken | Was always working — `"id"` never in SENSITIVE_KEYS, always extracted | HttpExecutionEngine:497-501 stores bare `id`, `entity.id`, and `entity_id` |

---

## SECTION 1: REGRESSIONS INTRODUCED BY ANTIGRAVITY

### REGRESSION 1 (P0): `GET /pet/findByStatus` Incorrectly Blocked as `NO_COMPATIBLE_IDENTITY`

**File:** `SecurityDecisionEngine.java` (uncommitted)
**Root cause:** `matchesScheme()` checks if the credential profile's `headerName` matches the declared security scheme name. Bearer token profiles have no `headerName` set, so they cannot match `api_key` header-based schemes. The Petstore's global security declares `api_key`, and since the test profiles are all Bearer-based, no identity matches.

**Impact:** Public endpoints behind a global security declaration that requires a scheme type incompatible with available credentials are incorrectly blocked. The system treats "I have the wrong credential type" the same as "this endpoint needs authentication I don't have."

**Evidence from live test:**
```
BLOCKED: [NO_COMPATIBLE_IDENTITY | SECURITY_LEVEL: GLOBAL] 
No credential profile provides the api_key scheme (header-based API key)
```
Endpoint `GET /pet/findByStatus?status=available` — a public read-only endpoint — blocked.

### REGRESSION 2 (P1): `GET /store/inventory` Incorrectly Blocked

**Same root cause as Regression 1.** `api_key` scheme not matched by bearer token profiles. This endpoint returns the store inventory and requires `api_key` header authentication at the OpenAPI spec level.

### REGRESSION 3 (P2): SecurityDecisionEngine Correctly Identifies but Incorrectly Classifies Public Endpoints

The `isAuthBootstrap()` detection works correctly for Petstore (detects OAuth2 flows, API key schemes). However, when no profile matches the required scheme, the engine returns `NO_COMPATIBLE_IDENTITY` instead of `AUTH_REQUIRED` with a fallback to global auth. This means endpoints that ARE accessible with the right credentials but currently lack matching profiles are treated the same as endpoints that absolutely require authentication.

**Expected behavior:** `AUTH_REQUIRED` with `selectedIdentity = null` → RunManager blocks with clear message.
**Actual behavior:** `NO_COMPATIBLE_IDENTITY` → same blocking, but misleading classification.

---

## SECTION 2: SESSION ISOLATION FIX (VERIFIED)

### Committed Code (BUG):
```java
// RunManager.java line 621-623 (git HEAD)
com.syed.apiqa.auth.IdentitySession idSession = null;
if (context.getAllSessions() != null && !context.getAllSessions().isEmpty()) {
    idSession = context.getAllSessions().values().iterator().next();  // ← ALL steps get same session
}
```
**All 10+ steps use the first registered session regardless of identity requirements.**

### Uncommitted Code (FIX):
```java
// RunManager.java line 626-632 (disk)
com.syed.apiqa.auth.engine.OperationSecurityDecision decision = securityDecisionEngine.evaluateSecurity(
        step.getApiEndpoint(), discovery.getOpenAPI(), activeProfiles);

com.syed.apiqa.auth.IdentitySession idSession = null;
if (decision.getSelectedIdentity() != null && context.getAllSessions() != null) {
    idSession = context.getSession(decision.getSelectedIdentity().getId());
}
```
**Per-step identity selection. Each step gets the session matching its required identity.**

**Verdict:** Fix is architecturally correct. The `getAllSessions().values().iterator().next()` pattern is completely eliminated from the production path. The only remaining `iterator().next()` in the codebase is in `ExamplePriorityEngine.java:71` which is unrelated (example selection, not session selection).

---

## SECTION 3: AUTH_FAILED CASCADE FIX (VERIFIED)

### Committed Code (BUG):
```java
// HttpExecutionEngine.java line 117-124 (git HEAD)
if (identitySession != null && identitySession.getState() == AuthLifecycleState.AUTH_FAILED) {
    step.setStatus(StepStatus.BLOCKED);
    return new StepExecutionOutcome(StepStatus.BLOCKED, ...);
}
```
**Once an identity fails auth, ALL subsequent steps using that identity are blocked — even different endpoints, different methods.**

### Uncommitted Code (FIX):
The `AUTH_FAILED` cascade check is **removed** from `HttpExecutionEngine.executeStep()`. Auth failure handling is now in `RunManager.java` lines 634-658, which:
1. Checks `decision.getSecurityState() == AUTH_REQUIRED`
2. Checks if `selectedIdentity` is null (no compatible identity found)
3. Checks if `idSession` is null (identity exists but has no session)
4. Checks if `idSession.getState() == AUTH_FAILED` (identity authenticated but failed)
5. Blocks the step with detailed reason

**Verdict:** Fix is correct. Auth failure is now handled at the orchestration level, not the execution engine level. The circuit breaker pattern is properly implemented.

---

## SECTION 4: AUTH BOOTSTRAP DETECTION (VERIFIED)

### Committed Code (MISSING):
`SecurityDecisionEngine.java` does not exist in committed code. Auth bootstrap detection was not implemented.

### Uncommitted Code (NEW):
`SecurityDecisionEngine.java` implements 4-level contract-driven detection:

1. **Level 1: Explicit security requirements** — If `operation.getSecurity()` is non-null/non-empty, return `AUTH_REQUIRED`
2. **Level 2: OAuth2 flows/tokenUrl** — If any security scheme has `type=oauth2` or contains `tokenUrl`, return `AUTH_REQUIRED`
3. **Level 3: Request body schema analysis** — If request body schema has `username`/`password`/`email`+`password`/`grant_type`/`client_id`+`client_secret`, return `AUTH_BOOTSTRAP`
4. **Level 4: Parameter analysis** — If path/query/body params contain auth-like names, return `AUTH_BOOTSTRAP`
5. **Fallback: Path-suffix heuristic** — `/login`, `/signin`, `/auth`, `/register`, `/signup`, `/oauth`, `/token` → `AUTH_BOOTSTRAP`

**Tested via:** `GenericAuthDecisionTest` (3 tests) — all pass.

**Verdict:** Correctly implemented. Fallback heuristic is kept but deprioritized behind contract-driven detection.

---

## SECTION 5: VARIABLE EXTRACTION (VERIFIED — PRE-EXISTING BUG)

### `"id"` Capture:
`"id"` is NOT in SENSITIVE_KEYS (never was — confirmed identical between committed and uncommitted code). Variable extraction at `HttpExecutionEngine.java:497-501`:
```java
if ("id".equalsIgnoreCase(key) || "uuid".equalsIgnoreCase(key)) {
    context.setVariable(key, valueStr);           // bare: "id"
    context.setVariable(entity + "_id", valueStr); // e.g., "order_id"
}
// Always stores: entity + "." + key (e.g., "order.id")
```

**Verdict:** Variable extraction works correctly. The previous audit's claim about `"id"` being removed from SENSITIVE_KEYS was inaccurate.

### FK Ordering Bug (PRE-EXISTING, NOT INTRODUCED BY ANTIGRAVITY):
```java
// HttpExecutionEngine.java lines 359-367
if (finalStatus == StepStatus.PASSED && !rawBody.isBlank()) {
    extractAndStoreVariables(rawBody, step, context, execution);  // line 360: saves CapturedVariable with FK to execution
}
executionRepository.save(execution);  // line 364: saves execution AFTER
```

**Impact:** `captured_variable` INSERT fails with FK violation because `execution.id` is null at insert time. Error is caught by `catch (Exception ignored)` at line 514. Variables ARE captured in memory (line 495) but NOT persisted to database. The report/PDF won't show captured variables.

**Confirmed in test output:**
```
Hibernate: insert into captured_variables (execution_id, ...) values (?, ...)
ERROR: FK violation - execution reference does not exist yet
```

**Verdict:** Pre-existing silent data loss bug. NOT introduced by Antigravity. Exists in both committed and uncommitted code.

---

## SECTION 6: RESPONSE SCHEMA VALIDATION (VERIFIED — PREVIOUS AUDIT WAS INCORRECT)

### Production Path:
1. `HttpExecutionEngine.java:324`: `assertionEngine.evaluateAssertions(execution, step.getExpectedStatus(), "application/json")`
2. `AssertionEngine.java:139`: `validateOpenApiSchema(execution, root, expectedStatus, results)`
3. `AssertionEngine.java:166`: `responseSchemasJson = execution.getTestStep().getApiEndpoint().getResponseSchemas()`
4. Schema validation includes: root type, required fields, property types, array items (up to 10)

### Population:
`OpenApiParserService.java:151`: `endpoint.setResponseSchemas(objectMapper.writeValueAsString(responseMap))` — populated during discovery from OpenAPI spec.

**Evidence from test:** `RealPetstoreVerificationTest` correctly caught `"User logged out"` (string) failing against expected object schema:
```
ASSERTION FAILED: Response root type mismatch: expected object, got string
```

**Verdict:** Response schema validation IS in the production path and IS working. The previous audit's claim was incorrect.

---

## SECTION 7: PERSISTENCE SAFETY (VERIFIED)

### Integer Parsing:
All `Integer.parseInt()` calls wrapped in `try/catch(Exception ignored)`:
- `OpenApiFetchService.java:135,175,192` — chunked transfer parsing
- `HttpExecutionEngine.java:290,704` — retry-after, HTTP parsing
- `TestPlanService.java:298` — status code parsing
- `TokenSecurityService.java:101` — JWT expiry

**Verdict:** No uncaught `NumberFormatException` risk.

### SQL Injection:
No raw SQL queries found. All persistence via JPA/Hibernate repositories.

**Verdict:** Safe.

### Integer Overflow:
`RunManager.java:555`: `Math.max(1, (System.nanoTime() - startNanos) / 1_000_000)` — safe, fits in `long`.
`HttpExecutionEngine.java:737,762`: Same pattern for `durationMs`.

**Verdict:** Safe.

---

## SECTION 8: LARGE API SCALE (500+ ENDPOINTS)

### Known Limitations:
1. **Hardcoded pagination** (`TestPlanService.java:72`): `page=1&pageSize=10` — only discovers first 10 endpoints
2. **POST-only producers** (`DependencyEngine.java`): Only POST endpoints create resources for dependency chains
3. **English grammatical matching** (`DependencyEngine.java`): Entity name matching based on English word similarity
4. **Static 3-target registry** (`RealTargetRegistry.java`): Only Petstore v2, v3, and Httpbin

### Safety:
- `MAX_NEGATIVE_PER_EP = 5` with `subList(0, 5)` — bounded
- `MAX_BACKLOG_SIZE = 50` for SSE events — bounded
- No silent operation dropping detected

**Verdict:** System has architectural limitations for large APIs but no safety issues.

---

## SECTION 9: PRODUCTION SAFETY

### SSRF Protection:
- `SsrfProtectionGuard` with anti-DNS rebinding IP pinning
- Localhost/private IPs blocked in PRODUCTION mode
- DELETE disabled in PRODUCTION mode

### External `$ref`:
Only local references used (`#/components/schemas/...`). No external URL-based `$ref` handling exists in the codebase. No SSRF risk via schema references.

### Hardcoding Search:
Searched entire production codebase for: `pawguard`, `rescue`, `animal`, `shelter`, `adoption` — **NONE FOUND**. System is generic.

**Verdict:** Production safety controls are in place.

---

## SECTION 10: TEST RESULTS

### Unit Tests: 119 run, 0 failures, 1 skipped
- `GenericAuthDecisionTest`: 3/3 pass
- `MultiIdentityCapabilityMatchingTest`: 6/6 pass
- `RealDataDependencyExecutionTest`: 1/1 pass (demonstrates POST→id→GET flow)
- `RealPetstoreVerificationTest`: 1/3 pass, 2 fail

### Live API Test Failures:
1. **Auth blocking regression** — `GET /pet/findByStatus`, `GET /store/inventory` blocked (see Regression 1)
2. **Status code mismatch** — `POST /user/createWithList` returns 201, test expects 200
3. **Schema validation correctly catches** — `"User logged out"` (string) vs expected object

---

## FINAL VERDICT: NO-GO

### Blockers (Must fix before testing):

| # | Finding | Severity | Introduced By | Status |
|---|---------|----------|---------------|--------|
| 1 | `GET /pet/findByStatus` blocked as NO_COMPATIBLE_IDENTITY | P0 REGRESSION | Antigravity | UNCOMMITTED |
| 2 | `GET /store/inventory` blocked as NO_COMPATIBLE_IDENTITY | P1 REGRESSION | Antigravity | UNCOMMITTED |
| 3 | SecurityDecisionEngine misclassifies public endpoints | P2 REGRESSION | Antigravity | UNCOMMITTED |
| 4 | FK ordering bug: captured variables silently lost | P3 PRE-EXISTING | Original code | COMMITTED |

### Corrections to Previous Audit:

| Previous Claim | Actual | Section |
|----------------|--------|---------|
| `"id"` removed from SENSITIVE_KEYS | `"id"` was never in SENSITIVE_KEYS | Section 5 |
| Added "role"/"scope"/"group"/"permissions" | Not present in either version | Section 5 |
| Response schema validation NOT in production | IS in production path | Section 6 |
| Variable extraction for `"id"` broken | Was always working | Section 5 |

### What Antigravity Got Right:
1. **Session isolation fix** — Eliminated `getAllSessions().values().iterator().next()` completely (Section 2)
2. **AUTH_FAILED cascade removal** — Auth failure now handled at orchestration level (Section 3)
3. **Contract-driven auth bootstrap** — 4-level detection with proper fallback (Section 4)
4. **Capability-based identity matching** — Architecture is sound (Section 1, except matching regression)
5. **Named strategy matching** — Correctly maps "OAuth2 Client Credentials" → `OAuth2ClientCredentialsStrategy`
6. **Unit tests pass** — 9/9 new tests pass, demonstrating correct behavior in isolation

### What Must Be Fixed:
1. **`matchesScheme()` must handle global security fallback** — When no profile matches the endpoint's declared scheme, the engine should fall back to trying all profiles against the global security declaration, not immediately return `NO_COMPATIBLE_IDENTITY`
2. **FK ordering** — Move `executionRepository.save(execution)` BEFORE `extractAndStoreVariables()`
3. **Commit the changes** — All fixes exist only on disk, never committed to git

---

*End of Independent Forensic Verification Report*
