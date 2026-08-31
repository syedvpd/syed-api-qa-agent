# Production Safety Architecture — Syed API QA Agent

## 1. Safety Philosophy

Because **Syed API QA Agent** tests live, deployed backends (including client staging and production systems), safety is a hard constraint. Testing must never compromise infrastructure, cause runaway load, overwrite unintended records, or leak private tokens.

---

## 2. Server-Side Request Forgery (SSRF) Defense

The agent resolves and validates host targets before any network connection is opened:

1. **Host & IP Address Verification**:
   - Hostnames are resolved to IP addresses prior to HTTP dispatch.
   - Any attempt to target loopback or private ranges is immediately rejected:
     - `127.0.0.0/8` (Loopback)
     - `10.0.0.0/8` (Private network Class A)
     - `172.16.0.0/12` (Private network Class B)
     - `192.168.0.0/16` (Private network Class C)
     - `169.254.169.254` (Cloud Instance Metadata Service — AWS/GCP/Azure)
     - `::1`, `fc00::/7`, `fe80::/10` (IPv6 loopback & link-local)
2. **Strict Protocol Enforcement**:
   - Only `http` and `https` protocols are permitted. `file://`, `ftp://`, `gopher://`, etc., are strictly blocked.
3. **Target Host Allowlist**:
   - When configured in the project settings, requests are strictly pinned to specified authorized domain patterns (e.g., `*.api.client.com`).

---

## 3. Environment Protection Profiles

Every `TestRun` operates under an explicitly declared Environment Profile:

| Setting | STAGING / DEV Mode | PRODUCTION Mode |
| :--- | :--- | :--- |
| `GET` Requests | Enabled | Enabled |
| `POST` Requests | Enabled (Full schema data) | Controlled (Rate-limited, test markers) |
| `PUT` / `PATCH` Requests | Enabled | Restricted (Requires explicit flag) |
| `DELETE` Requests | Enabled | **Disabled by default** (Requires explicit confirmation) |
| Concurrency Limit | Up to 10 concurrent workers | Bounded (1 to 2 workers max) |
| Rate Limit | 20 requests / sec | 2 to 5 requests / sec |
| Maximum Total Requests | 5,000 | 500 |

---

## 4. Resource Isolation & Cleanup Safety

1. **Tagging & Traceability**:
   - All generated strings and objects include an identifiable test prefix: `SYED_QA_<RUN_ID>_...`.
   - Headers include `X-Syed-QA-Agent-Run: <runId>` to allow backend administrators to track and isolate test traffic in server logs.
2. **Reverse Topological Cleanup**:
   - When cleanup is active, created resources are deleted in the reverse order of creation (e.g., child items before parent containers).
   - If a cleanup step fails, the failure is recorded as `CLEANUP_FAILED`, and remaining cleanup steps continue.
