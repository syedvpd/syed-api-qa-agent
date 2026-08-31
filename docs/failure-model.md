# Failure Model & Blast-Radius Isolation — Syed API QA Agent

## 1. Failure Philosophy

In conventional integration test runners, an unhandled exception or failed assertion halts the entire test suite. In **Syed API QA Agent**, **one failure must NEVER terminate the test run**. 

Failures are isolated strictly to the affected branch of the dependency graph, allowing all independent endpoints and operations to continue execution.

---

## 2. Test Execution States

Every `TestStep` and `TestCase` transitions into one of the following deterministic states:

| Status | Description |
| :--- | :--- |
| `PASSED` | All assertions (status code, schema, required fields, timing thresholds) succeeded. |
| `FAILED` | One or more assertions failed against the server's live response. |
| `WARNING` | Endpoint succeeded (e.g. 200 OK), but minor contract inconsistencies occurred (e.g. extra undocumented headers or non-fatal deprecation warnings). |
| `BLOCKED` | Step could not execute because an upstream prerequisite step failed (e.g. `POST /users` failed, so `GET /users/{id}` is blocked). |
| `SKIPPED` | Step was deliberately omitted due to configuration (e.g. `DELETE` disabled in Production mode). |
| `TIMEOUT` | HTTP client reached the socket read or connection timeout limit. |
| `NETWORK_ERROR` | Connection refused, DNS lookup failure, or host unreachable. |
| `AUTHENTICATION_ERROR`| HTTP 401 Unauthorized received where valid credentials were expected. |
| `AUTHORIZATION_ERROR` | HTTP 403 Forbidden received. |
| `RATE_LIMITED` | HTTP 429 Too Many Requests encountered. |
| `CONTRACT_ERROR` | Response JSON violated the declared OpenAPI schema (missing required fields, incorrect data types). |
| `CLEANUP_FAILED` | An error occurred while attempting to tear down a resource created during the run. |
| `UNKNOWN` | Uncategorized execution outcome requiring manual inspection. |

---

## 3. Blast-Radius Isolation

When step $S_i$ fails:
1. The engine inspects the dependency graph downstream of $S_i$.
2. All child steps that directly or indirectly require variables produced by $S_i$ are marked **`BLOCKED`** with a reason `PREREQUISITE_FAILED (Step: Si)`.
3. Execution immediately switches to the next independent root operation in the queue.
4. The test run's overall progress bar continues smoothly without crashing.

```
       [POST /users]  ──▶ (FAILED 500 Internal Error)
             │
             ├──▶ [GET /users/{id}]     [BLOCKED: Prerequisite POST /users failed]
             ├──▶ [PUT /users/{id}]     [BLOCKED: Prerequisite POST /users failed]
             └──▶ [DELETE /users/{id}]  [BLOCKED: Prerequisite POST /users failed]

       [GET /products] ───────────────▶ [PASSED 200 OK (Independent execution)]
       [GET /health]   ───────────────▶ [PASSED 200 OK (Independent execution)]
```

---

## 4. Expected Status Inversion (Negative & Verification Testing)

A non-2xx status code is **not** automatically a failure:
- When testing `GET /users/{deletedId}` after a successful deletion, an HTTP `404 Not Found` is the **expected** contract behavior. The step is marked `PASSED`.
- If the endpoint returns `200 OK` when querying a deleted resource, the step is marked `FAILED` (Zombie Resource / State Corruption).
