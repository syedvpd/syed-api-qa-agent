# Production Safety Architecture — Syed API QA Agent

## 1. Safety Philosophy

Because **Syed API QA Agent** tests live, deployed backends (including client staging and production systems), safety is a hard constraint. Testing must never compromise infrastructure, cause runaway load, overwrite unintended records, leak private tokens, or be susceptible to spoofing or SSRF.

---

## 2. Server-Side Request Forgery (SSRF) & Anti-DNS Rebinding Architecture

The agent resolves, validates, and pins host targets before any network connection is opened:

1. **Host & IP Address Verification**:
   - Hostnames are resolved to IP addresses prior to HTTP dispatch.
   - Any attempt to target loopback or private ranges is immediately rejected:
     - `127.0.0.0/8` (Loopback)
     - `10.0.0.0/8` (Private network Class A)
     - `172.16.0.0/12` (Private network Class B)
     - `192.168.0.0/16` (Private network Class C)
     - `169.254.169.254`, `100.100.100.200` (Cloud Instance Metadata Services)
     - `100.64.0.0/10` (Carrier-Grade NAT)
     - `::1`, `fc00::/7`, `fe80::/10`, `::ffff:127.0.0.1` (IPv6 loopback, site-local, link-local, and IPv4-mapped IPv6)
     - `0.0.0.0` (Wildcard / any-local)
2. **Strict Protocol & URL Enforcement**:
   - Only `http` and `https` protocols are permitted. `file://`, `ftp://`, `gopher://`, etc., are strictly blocked.
   - Userinfo in URLs (e.g. `http://user:pass@host/`) is strictly prohibited.
3. **Anti-DNS Rebinding (IP Pinning)**:
   - Eliminates Time-of-Check to Time-of-Use (TOCTOU) race conditions:
     - Target hostname is resolved and every returned IP is validated.
     - Outbound sockets connect directly to the pinned IP address.
     - The HTTP `Host` header is preserved with the virtual host for correct reverse-proxy routing.
     - For HTTPS, TLS Server Name Indication (SNI) and hostname verification validate against the original domain.
4. **Redirect Validation**:
   - All HTTP redirects (301, 302, 307, 308) re-run full SSRF and anti-rebinding checks against the redirect destination.

---

## 3. Cryptographic Authentication & Tenant Isolation

1. **HMAC-SHA256 Token Authentication**:
   - Replaces spoofable `X-User-Id` headers with cryptographic Bearer tokens.
   - Tokens contain tamper-proof payload signed with server secret (`SYED_AUTH_SECRET`).
   - Constant-time verification prevents timing attacks.
   - Forged identity headers (e.g. sending valid token for Alice while passing `X-User-Id: bob`) are detected and rejected with HTTP 403 Forbidden.
2. **Server-Side Ownership Enforcement**:
   - All protected resources (`/api/runs/**`, `/api/schedules/**`, reports, SSE event streams) enforce server-side ownership.
   - Historical regression baselines cannot cross tenants.

---

## 4. Encryption-at-Rest for Secrets (AES-256-GCM)

1. **Encrypted Storage**:
   - Sensitive database columns (`auth_token`, `auth_login_payload`, `auth_credentials`) are encrypted at rest using AES-256-GCM with a 12-byte random IV and 128-bit authentication tag.
   - Master encryption key is loaded from `SYED_ENCRYPTION_KEY`.
2. **Response Redaction**:
   - Fields containing raw credentials are annotated with `@JsonIgnore` and never serialized in API responses.
   - HTML and PDF reports sanitize request/response fragments through `SecretMasker`.
   - Variable capture excludes sensitive keys (`password`, `token`, `secret`, `api_key`, `credentials`, etc.).

---

## 5. Environment Protection Profiles

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

## 6. Resource Isolation & Cleanup Safety

1. **Tagging & Traceability**:
   - All generated strings and objects include an identifiable test prefix: `SYED_QA_<RUN_ID>_...`.
   - Headers include `X-Syed-QA-Agent-Run: <runId>` to allow backend administrators to track and isolate test traffic in server logs.
2. **Reverse Topological Cleanup**:
   - When cleanup is active, created resources are deleted in the reverse order of creation (e.g., child items before parent containers).
   - If a cleanup step fails, the failure is recorded as `CLEANUP_FAILED`, and remaining cleanup steps continue.
   - In `PRODUCTION` mode, automated teardown is bypassed to prevent accidental data destruction.
