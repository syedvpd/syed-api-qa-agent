# Execution Engine Architecture — Syed API QA Agent

## 1. Overview & Purpose

The **Execution Engine** is responsible for dispatching HTTP requests to the target deployed backend, managing connection lifecycles, accurately recording timing and latency metrics, resolving dynamic context variables, evaluating assertions, and persisting granular execution evidence.

---

## 2. Request Lifecycle Pipeline

Every single test step executes through a strictly sequenced 14-stage pipeline:

```
 ┌────────────────────────────────────────────────────────┐
 │ 1. Variable Resolution (resolve {{context.variables}})  │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 2. Safety & SSRF Pre-Check (target host validation)   │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 3. URL Construction (Path template substitution)       │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 4. Header & Query Parameter Formatting                 │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 5. Body Serialization & Content-Type Negotiation       │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 6. Authentication Injection (Bearer/Basic/API-Key)     │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 7. High-Precision Timer Start (System.nanoTime)        │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 8. HTTP Dispatch (Spring RestClient / Apache Client)   │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 9. High-Precision Timer Stop & Latency Capture (ms)    │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 10. Raw Response & Header Ingestion (Size bounded)     │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 11. Assertion Evaluation (Status, Schema, Fields)      │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 12. Context Extraction (Capture dynamic variables)     │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 13. Evidence Sanitization (Mask secrets in headers)    │
 └───────────────────────────┬────────────────────────────┘
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │ 14. Persistence & SSE Event Dispatch                   │
 └────────────────────────────────────────────────────────┘
```

---

## 3. Variable Resolution & Scoping

Dynamic variables use double-curly-bracket notation: `{{entity.field}}` (e.g., `{{user.id}}` or `{{auth.token}}`).

- **Resolution Order**:
  1. Step-local context (variables created in previous steps of the current test case).
  2. Run-level shared context (global tokens, environment settings, shared setup outputs).
  3. Default literal fallback if allowed by schema.
- **Unresolved Variables**: If a required path or body variable cannot be resolved (e.g., because the parent step that produces `user.id` failed or returned a 500), the step status is immediately flagged as **`BLOCKED`** rather than attempting a malformed HTTP request.

---

## 4. Safe Retry Mechanics

Uncontrolled retries against live APIs can trigger duplicate database records, double-billing, or cascading denial of service. The Execution Engine enforces strict idempotency rules:

| HTTP Verb | Safe to Retry on Network Timeout? | Default Policy |
| :--- | :--- | :--- |
| `GET` | **Yes** (Idempotent) | Up to 2 retries with exponential backoff (200ms, 400ms). |
| `HEAD` | **Yes** (Idempotent) | Up to 2 retries. |
| `OPTIONS` | **Yes** (Idempotent) | Up to 2 retries. |
| `PUT` | **Conditionally** (Idempotent if full replacement) | Configurable; disabled by default in production. |
| `DELETE` | **Conditionally** (Idempotent) | 0 retries by default; subsequent attempts may produce 404. |
| `PATCH` | **No** (Potentially non-idempotent) | 0 retries on timeout. |
| `POST` | **NEVER** (Non-idempotent) | **Zero automatic retries**. If a POST times out, the outcome is classified as `TIMEOUT` / `UNCERTAIN_STATE` and execution proceeds to safe isolation. |

---

## 5. Secret Masking & Redaction

Before any request or response payload is stored in the database or serialized into event logs, it passes through the `SecretSanitizer`:
- Headers: `Authorization`, `Proxy-Authorization`, `X-Api-Key`, `Cookie`, `Set-Cookie`, and any header ending in `Token` or `Secret` have their values masked to `[REDACTED]`.
- JSON Bodies: Keys matching regex patterns `(?i)(password|secret|token|api[_-]?key|credit[_-]?card|cvv)` have their values masked before persistence.
