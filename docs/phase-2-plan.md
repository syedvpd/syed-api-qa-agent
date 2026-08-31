# Phase 2 Implementation Plan: Advanced Test Generation, Dynamic Auth & Automated Teardown

## 1. Objectives & Scope

Phase 2 advances the Syed API QA Agent from basic CRUD discovery into autonomous negative robustness validation, dynamic authentication lifecycles, and safe reverse-dependency teardown against live/deployed target APIs without any LLM dependency.

```
Phase 1 Foundation
  ├── OpenAPI Ingestion & Heuristic Graph
  ├── Seeded Valid Deterministic Generation
  └── Java 21 HTTP Execution & Failure Isolation
         ↓
Phase 2 Advancements
  ├── 2A: Negative & Boundary Fuzzing Engine (Missing fields, wrong types, invalid enums, boundary values, invalid formats)
  ├── 2B: Negative Response Matrix (400/422 contract validations = PASS, unexpected 500 = FAIL)
  ├── 2C: Malformed Input Testing (Empty/null bodies, malformed JSON, empty strings, whitespace)
  ├── 2D: Non-destructive Security Probes (SQLi markers, script tags, quotes, Unicode boundaries)
  ├── 2E & 2F: Dynamic Auth & Token Refresh (Login endpoint, JWT/token extraction, {{auth.token}} injection, 401 refresh & safe retry)
  └── 2G: Automated Resource Cleanup (Reverse topological teardown of created resources, production DELETE safety gate)
```

---

## 2. Database Schema Additions (`V3__phase2_enhancements.sql`)

### 1. New Table `cleanup_records`
Tracks every created resource for deterministic reverse-topological deletion:
- `id` (VARCHAR(36) PRIMARY KEY)
- `test_run_id` (VARCHAR(36) REFERENCES test_runs(id))
- `resource_type` (VARCHAR(64))
- `resource_id` (VARCHAR(256))
- `delete_endpoint` (VARCHAR(512))
- `execution_order` (INT)
- `status` (VARCHAR(32)) -- PENDING, COMPLETED, FAILED, SKIPPED
- `error_message` (TEXT)
- `created_at` (TIMESTAMP WITH TIME ZONE)
- `cleaned_at` (TIMESTAMP WITH TIME ZONE)

### 2. Additions to `test_cases`
- `category` (VARCHAR(32)) -- POSITIVE_CRUD, NEGATIVE_VALIDATION, BOUNDARY_LIMITS, MALFORMED_INPUT, SECURITY_PROBE, AUTH_FLOW, CLEANUP_TEARDOWN

### 3. Additions to `test_runs`
- `auth_login_url` (VARCHAR(512))
- `auth_login_payload` (TEXT)
- `auth_token_path` (VARCHAR(128)) -- e.g. "token", "access_token", "data.jwt"
- `auth_refresh_url` (VARCHAR(512))
- `cleanup_status` (VARCHAR(32)) -- NOT_RUN, EXECUTED, PARTIAL, SKIPPED

---

## 3. Component Architecture & Implementation

### 3.1 Negative & Boundary Synthesizer (`com.syed.apiqa.generation.NegativeDataGenerator`)
- **Missing Required Fields**: Drops one required property at a time to test targeted validation.
- **Wrong Types**: Replaces integers with strings (`"abc"`), booleans with integers (`999`), strings with arrays (`[1, 2]`), objects with primitives.
- **Invalid Enums**: Generates `__INVALID_ENUM_VALUE__` and lowercase variants.
- **Boundary Values**:
  - Numbers: `minimum - 1`, `maximum + 1`, `minimum`, `maximum`.
  - Strings: length `minLength - 1`, length `maxLength + 1`.
  - Arrays: `minItems - 1`, `maxItems + 1`.
- **Invalid Formats**:
  - `email`: `"not-an-email"`, `"missing@domain"`
  - `uuid`: `"invalid-uuid-1234"`
  - `date` / `date-time`: `"invalid-date"`, `"9999-99-99"`
  - `uri`: `"not-a-valid-uri"`
- **Malformed Payloads**: Empty JSON (`{}`), null string (`"null"`), corrupted JSON syntax (`{"key": "value",}`).
- **Safe Security Probes**:
  - Injection markers: `' OR '1'='1`, `1; DROP TABLE test--`
  - XSS tags: `<script>alert(1)</script>`, `"><img src=x onerror=alert(1)>`
  - Unicode & Whitespace: `\u0000`, `\uFFFF`, leading/trailing tabs and newlines.

### 3.2 Negative Assertion Matrix (`com.syed.apiqa.assertion.AssertionEngine`)
- Distinguishes **expected validation rejection** from server crashes:
  - If a step is `NEGATIVE_VALIDATION` or `MALFORMED_INPUT`:
    - Status `400` (Bad Request) or `422` (Unprocessable Entity) &rarr; **PASSED** (server correctly rejected invalid data).
    - Status `200/201` &rarr; **FAILED** (server allowed invalid/illegal data).
    - Status `500/502/503` &rarr; **FAILED** (unhandled server crash, internal server error).

### 3.3 Dynamic Authentication & Token Refresh (`com.syed.apiqa.auth.DynamicAuthService`)
- Performs pre-execution login to `authLoginUrl` with `authLoginPayload`.
- Extracts token using dot-notation JSON path (e.g. `token`, `data.token`, `access_token`).
- Injects token into `ExecutionContext` as `auth.token` and sets `Authorization: Bearer {{auth.token}}`.
- Handles `401 Unauthorized` during test execution:
  - If `authRefreshUrl` or login is configured, triggers dynamic token refresh.
  - Replaces token in context.
  - Retries the failed request ONLY if it is safe and idempotent (`GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE`). Suppresses retry on `POST` timeout or non-idempotent operations.

### 3.4 Automated Resource Cleanup (`com.syed.apiqa.cleanup.ResourceCleanupManager`)
- Records newly created resource IDs (`POST /users` &rarr; `id: 123`) in `cleanup_records`.
- Maps corresponding `DELETE` endpoint using dependency graph.
- During stage `CLEANUP`:
  - If `isProduction` and destructive operations are disabled: skips teardown, logs `CLEANUP_SKIPPED: destructive operations disabled in PRODUCTION`.
  - Otherwise, executes `DELETE` in strict reverse order of creation:
    1. Child resources first (e.g. `DELETE /orders/456`)
    2. Parent resources second (e.g. `DELETE /users/123`)
  - If an individual cleanup step returns 404 or 500, marks that record `CLEANUP_FAILED`, preserves the failure in the database, and continues cleaning up all remaining independent resources.

---

## 4. Verification & Testing Strategy

Automated test suite `Phase2AdvancedPipelineTest`:
1. Negative required field rejection (WireMock returns 422 &rarr; step passes; returns 500 &rarr; step fails).
2. Boundary value testing (`minimum - 1`, `maximum + 1`).
3. Invalid enum & format testing (`email`, `uuid`).
4. Dynamic authentication login & token propagation (`POST /auth/login` &rarr; bearer token extracted and injected into downstream calls).
5. Token expiration (401 triggers token refresh and safe retry).
6. Reverse-topological resource cleanup execution.
7. Cleanup skip when production DELETE is blocked.
8. Secret masking across dynamic auth headers and bodies.
