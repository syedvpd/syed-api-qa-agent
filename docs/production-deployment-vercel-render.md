# Syed API QA Agent — Vercel + Render Production Deployment Guide

This document is the definitive, step-by-step instruction manual for deploying the **Syed API QA Agent** to production using **Vercel** (Frontend), **Render Web Service** (Backend), and **Render Managed PostgreSQL** (Database).

All instructions, environment variable names, port assignments, and configuration parameters in this guide are derived strictly from the current repository source code.

---

# 1. Final Production Architecture

```
                                  Internet
                                     │
                                     ▼ HTTPS (Port 443)
                      ┌──────────────────────────────┐
                      │    Vercel Edge Network       │
                      │  Next.js 14 Frontend App     │
                      │  https://<vercel-app>.app    │
                      └──────────────┬───────────────┘
                                     │
                                     │ HTTPS / WSS / SSE (Port 443)
                                     ▼
                      ┌──────────────────────────────┐
                      │   Render Cloud Network       │
                      │   Spring Boot Backend        │
                      │   https://<render-api>.com   │
                      │   (Container Port: 8080)     │
                      └──────────────┬───────────────┘
                                     │
                                     │ Private Network (Port 5432)
                                     │ [NO PUBLIC ACCESS]
                                     ▼
                      ┌──────────────────────────────┐
                      │   Render Managed PostgreSQL  │
                      │   Database: syed_apiqa       │
                      │   Internal Host: dpg-xxxx-a  │
                      └──────────────────────────────┘
```

### Public vs. Private Network Boundaries
- **Public Endpoints**:
  - **Vercel Frontend**: Accessible to end users over public HTTPS.
  - **Render Backend**: Accessible to the Vercel frontend and automated CI/CD runners over public HTTPS (`/api/**`).
- **Private Boundary**:
  - **Render Managed PostgreSQL**: Kept strictly within Render's private network. Connections are routed via Render's **Internal Database URL** / private service discovery hostname (`dpg-xxxx-a`). External public database access is disabled.

---

# 2. Vercel Frontend Configuration

Configure the project in the [Vercel Dashboard](https://vercel.com/new) with the following settings derived from `frontend/package.json` and `frontend/next.config.js`:

| Vercel Setting | Exact Required Value | Source / Verification |
| :--- | :--- | :--- |
| **Framework Preset** | `Next.js` | Identified by Next.js 14 App Router in `frontend/package.json` |
| **Root Directory** | `frontend` | Frontend source code resides in the `frontend` subdirectory |
| **Build Command** | `npm run build` (or Next.js default) | Generates production bundles via `next build` |
| **Output Directory** | `.next` (default) | Standard Next.js build output |
| **Install Command** | `npm install` | Installs dependencies specified in `frontend/package.json` |
| **Node.js Version** | `20.x` or `22.x` | Compatible with Node.js 20+ runtime |

### Vercel Environment Variables

| Variable Name | Environment | Value Format | Description |
| :--- | :--- | :--- | :--- |
| `NEXT_PUBLIC_API_URL` | Production | `https://<your-render-backend-domain>` | **Required**. Must point to your live Render backend HTTPS URL (e.g., `https://syed-apiqa-backend.onrender.com`). **Do not add a trailing slash**. |
| `NEXT_PUBLIC_API_URL` | Preview | `https://<your-render-backend-domain>` | Required for Vercel preview branch deployments. |
| `NEXT_PUBLIC_API_URL` | Development | `http://localhost:8080` | Used during local `npm run dev` development. |

> [!IMPORTANT]
> A comprehensive scan of all TypeScript files in `frontend/src` confirms that `NEXT_PUBLIC_API_URL` is the **only** `NEXT_PUBLIC_*` variable consumed by the client. No other frontend runtime environment variables are required.

---

# 3. Render Backend Configuration

Create a new **Web Service** in the [Render Dashboard](https://dashboard.render.com) connected to your GitHub repository:

| Render Setting | Exact Required Value | Notes |
| :--- | :--- | :--- |
| **Service Type** | `Web Service` | Managed container web service |
| **Repository** | `https://github.com/syedvpd/syed-api-qa-agent` | Your GitHub repository |
| **Branch** | `main` | Production release branch |
| **Root Directory** | `backend` | Backend source folder |
| **Runtime** | `Docker` | Uses `backend/Dockerfile` |
| **Dockerfile Path** | `Dockerfile` (relative to `backend` root) | Multi-stage Eclipse Temurin 21 build |
| **Port** | `8080` | Configured in `application.yml` (`server.port: 8080`) and exposed in `backend/Dockerfile` |
| **Health Check Path** | `/api/health` | Verified endpoint returning HTTP 200 with service metadata |
| **Region** | Select same region as PostgreSQL | E.g., `Oregon (US West)` or `Frankfurt (EU Central)` to minimize latency |
| **Plan** | Starter or higher | Requires at least 512MB RAM for OpenJDK 21 runtime |
| **Auto-Deploy** | `Yes` | Automatically triggers on new commits to `main` |
| **Persistent Disk** | `None` | Not required. Application is stateless; all state is persisted in PostgreSQL |

---

# 4. Render PostgreSQL Configuration

Create a new **PostgreSQL** database in the [Render Dashboard](https://dashboard.render.com/new/database):

| Database Setting | Recommended Value | Notes |
| :--- | :--- | :--- |
| **Name** | `syed-apiqa-db` | Name of the Render service |
| **Database** | `syed_apiqa` | Target database name |
| **User** | `apiqa_user` | Target database user |
| **Region** | Same region as Backend Web Service | Essential for co-located sub-millisecond database queries |
| **PostgreSQL Version** | `16` | Fully compatible with Flyway migrations V1 through V8 |
| **Plan** | Starter or Free | |

### Connecting Backend to Render PostgreSQL
Render provides two connection URLs in the database dashboard:
1. **Internal Database URL** (e.g. `postgres://apiqa_user:password@dpg-xxxx-a:5432/syed_apiqa`): Used for services inside Render.
2. **External Database URL**: Used for external admin access.

> [!CAUTION]
> Spring Boot requires the standard JDBC format (`jdbc:postgresql://...`), whereas Render's `DATABASE_URL` uses the URI format (`postgres://...`).

You must configure the backend using standard Spring datasource properties in the Render Web Service Environment settings:
- `SPRING_DATASOURCE_URL`: `jdbc:postgresql://<Internal-Host>:5432/<Database-Name>`
  *(Example: `jdbc:postgresql://dpg-cuid12345678-a:5432/syed_apiqa`)*
- `SPRING_DATASOURCE_USERNAME`: `<Render-Database-User>` *(Example: `apiqa_user`)*
- `SPRING_DATASOURCE_PASSWORD`: `<Render-Database-Password>`

---

# 5. COMPLETE Environment Variable Table

| Variable | Service | Required | Format / Example | Secret? | Where Obtained / Configured |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `NEXT_PUBLIC_API_URL` | Vercel (Frontend) | **YES** | `https://syed-apiqa-backend.onrender.com` | No | Render Backend Dashboard (Public URL) |
| `SERVER_PORT` | Render (Backend) | **YES** | `8080` | No | Fixed application port |
| `SPRING_DATASOURCE_URL` | Render (Backend) | **YES** | `jdbc:postgresql://<internal-host>:5432/syed_apiqa` | Yes | Render PostgreSQL Internal Host |
| `SPRING_DATASOURCE_USERNAME` | Render (Backend) | **YES** | `apiqa_user` | Yes | Render PostgreSQL Credentials |
| `SPRING_DATASOURCE_PASSWORD` | Render (Backend) | **YES** | `secret_password_here` | Yes | Render PostgreSQL Credentials |
| `SYED_AUTH_SECRET` | Render (Backend) | **YES** | 32+ char base64 string | **YES** | Generated via `openssl rand -base64 32` |
| `SYED_ENCRYPTION_KEY` | Render (Backend) | **YES** | 32+ char base64 string | **YES** | Generated via `openssl rand -base64 32` |
| `SYED_SECURITY_AUTH_ENABLED` | Render (Backend) | **YES** | `true` | No | Hardened production authentication |
| `SYED_SECURITY_ALLOWED_ORIGINS`| Render (Backend) | **YES** | `https://<vercel-domain>.vercel.app` | No | Vercel Frontend Domain |
| `SYED_SAFETY_SSRF_PROTECTION_ENABLED` | Render (Backend) | **YES** | `true` | No | Enables SSRF IP pinning guard |
| `SYED_SAFETY_PRODUCTION_DELETE_ENABLED` | Render (Backend) | **YES** | `false` | No | Suppresses destructive DELETE requests |
| `SYED_SAFETY_MAX_CONCURRENCY` | Render (Backend) | Optional | `5` | No | Fair concurrency limiter |
| `SYED_SAFETY_DEFAULT_TIMEOUT_SECONDS` | Render (Backend) | Optional | `15` | No | Outbound HTTP timeout watchdog |
| `SYED_SAFETY_MAX_RESPONSE_SIZE_BYTES` | Render (Backend) | Optional | `2097152` | No | 2MB payload truncation threshold |

---

# 6. Secret Generation Commands

The application requires two cryptographically distinct 256-bit secrets:
1. `SYED_AUTH_SECRET`: Used by `TokenSecurityService` for HMAC-SHA256 token signing and verification.
2. `SYED_ENCRYPTION_KEY`: Used by `EncryptedStringConverter` for AES-256-GCM column encryption-at-rest.

### Linux / macOS (Terminal)
```bash
# 1. Generate SYED_AUTH_SECRET:
openssl rand -base64 32

# 2. Generate SYED_ENCRYPTION_KEY:
openssl rand -base64 32
```

### Windows (PowerShell)
```powershell
# 1. Generate SYED_AUTH_SECRET:
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32; $rng.GetBytes($bytes)
[Convert]::ToBase64String($bytes)

# 2. Generate SYED_ENCRYPTION_KEY:
$bytes2 = New-Object byte[] 32; $rng.GetBytes($bytes2)
[Convert]::ToBase64String($bytes2)
```

> [!CAUTION]
> Copy the output of each command into Render Web Service environment variables. These secrets must remain persistent across redeployments; regenerating `SYED_ENCRYPTION_KEY` will invalidate existing encrypted database records.

---

# 7. Render Backend Deployment Step-by-Step

1. **Create Managed Database**:
   - Go to [Render Dashboard](https://dashboard.render.com) &rarr; **New +** &rarr; **PostgreSQL**.
   - Set Name: `syed-apiqa-db`, Database: `syed_apiqa`, User: `apiqa_user`.
   - Click **Create Database**.
2. **Retrieve Internal Database Credentials**:
   - On the database dashboard, locate the **Internal Database URL** or **Connections** section.
   - Note the **Internal Host** (e.g. `dpg-xxxx-a.render.internal` or `dpg-xxxx-a`), the User, and Password.
3. **Create Backend Web Service**:
   - In Render Dashboard &rarr; **New +** &rarr; **Web Service**.
   - Connect repository `https://github.com/syedvpd/syed-api-qa-agent`.
   - Set Name: `syed-apiqa-backend`.
   - Set Root Directory: `backend`.
   - Set Runtime: `Docker`.
   - Set Dockerfile Path: `Dockerfile`.
4. **Configure Environment Variables**:
   - Add all variables from Section 5:
     ```env
     SERVER_PORT=8080
     SPRING_DATASOURCE_URL=jdbc:postgresql://<Internal-Host>:5432/syed_apiqa
     SPRING_DATASOURCE_USERNAME=apiqa_user
     SPRING_DATASOURCE_PASSWORD=<Your-Postgres-Password>
     SYED_AUTH_SECRET=<Generated-Auth-Secret>
     SYED_ENCRYPTION_KEY=<Generated-Encryption-Key>
     SYED_SECURITY_AUTH_ENABLED=true
     SYED_SAFETY_SSRF_PROTECTION_ENABLED=true
     SYED_SAFETY_PRODUCTION_DELETE_ENABLED=false
     SYED_SECURITY_ALLOWED_ORIGINS=https://<your-vercel-domain>.vercel.app
     ```
5. **Deploy Backend**:
   - Click **Create Web Service**.
   - Render will build the Eclipse Temurin 21 Docker container and start the application.
6. **Verify Startup & Flyway in Logs**:
   - Inspect the deployment log and confirm:
     - `Flyway Community Edition 10.x by Redgate`
     - `Successfully applied 8 migrations to schema "public"`
     - `Started SyedApiQaApplication in X seconds`
7. **Verify Health Endpoint**:
   - Once deployment completes, verify in your terminal:
     ```bash
     curl -i https://syed-apiqa-backend.onrender.com/api/health
     ```
   - Expect `HTTP 200 OK` with JSON:
     ```json
     {
       "status": "UP",
       "service": "syed-api-qa-agent",
       "version": "1.0.0",
       "phase": "PRODUCTION_HARDENED"
     }
     ```
8. **Record Public URL**:
   - Note the public backend URL (e.g., `https://syed-apiqa-backend.onrender.com`).

---

# 8. Vercel Frontend Deployment Step-by-Step

1. **Import Repository**:
   - Open [Vercel Dashboard](https://vercel.com/new) and select your GitHub repository `syedvpd/syed-api-qa-agent`.
2. **Configure Project Settings**:
   - Set **Framework Preset**: `Next.js`.
   - Set **Root Directory**: Click Edit and select `frontend`.
3. **Set Environment Variable**:
   - Under **Environment Variables**, add:
     - **Key**: `NEXT_PUBLIC_API_URL`
     - **Value**: `https://syed-apiqa-backend.onrender.com` (Your Render backend URL without trailing slash)
     - Select **Production**, **Preview**, and **Development**.
4. **Deploy**:
   - Click **Deploy**.
   - Vercel compiles the Next.js App Router application and assigns your production domain (e.g. `https://syed-api-qa-agent.vercel.app`).
5. **Update Render CORS Allowed Origins**:
   - Copy your assigned Vercel URL (e.g. `https://syed-api-qa-agent.vercel.app`).
   - In the Render Backend Web Service &rarr; **Environment**, set:
     ```env
     SYED_SECURITY_ALLOWED_ORIGINS=https://syed-api-qa-agent.vercel.app,https://*-syedvpd.vercel.app
     ```
   - Render will automatically restart the backend with the new allowed origins.

---

# 9. CORS Configuration

The backend CORS policy is implemented in [WebConfig.java](file:///c:/Users/HP/Desktop/API%20TESTING%20AGENT%20SYED/backend/src/main/java/com/syed/apiqa/config/WebConfig.java):
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${syed.security.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOriginPatterns(origins.length > 0 ? origins : new String[]{"*"})
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

### Required Configuration for Vercel
Set the following environment variable in your Render backend:
```env
SYED_SECURITY_ALLOWED_ORIGINS=https://your-frontend.vercel.app,https://*-your-team.vercel.app
```
- Because the backend uses `.allowedOriginPatterns()`, wildcards like `https://*-your-team.vercel.app` work properly with `allowCredentials(true)`.
- Preflight `OPTIONS` requests are handled automatically, allowing all headers (`Authorization`, `Content-Type`, `X-User-Id`, `Idempotency-Key`).

---

# 10. Authentication & Security Model

The system uses stateless, cryptographically signed Bearer tokens implemented in [TokenSecurityService.java](file:///c:/Users/HP/Desktop/API%20TESTING%20AGENT%20SYED/backend/src/main/java/com/syed/apiqa/security/TokenSecurityService.java) and [AuthSecurityFilter.java](file:///c:/Users/HP/Desktop/API%20TESTING%20AGENT%20SYED/backend/src/main/java/com/syed/apiqa/security/AuthSecurityFilter.java).

### Token Issuance Endpoint
Generate an authentication token for any user identity:
```bash
curl -X POST https://syed-apiqa-backend.onrender.com/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "lead-qa-syed"}'
```
Response:
```json
{
  "token": "syed_sec_v1.bGVhZC1xYS1zeWVkOjE3ODgyNzE0MTY.vF8...",
  "tokenType": "Bearer",
  "userId": "lead-qa-syed",
  "expiresAt": "2026-09-02T19:30:00Z"
}
```

### Client Request Headers
To invoke protected APIs, the client sends:
```http
Authorization: Bearer syed_sec_v1.<payload>.<signature>
```
- **Anti-Spoofing Guarantee**: If a client sends an `X-User-Id` header that differs from the cryptographically verified token identity, `AuthSecurityFilter` immediately terminates the request with `HTTP 403 Forbidden` (`FORGED_IDENTITY`).

---

# 11. Server-Sent Events (SSE) Streaming

Real-time execution streaming is served at:
```http
GET /api/runs/{id}/events
```

### Browser Header Limitation & Workaround
Standard browser JavaScript `new EventSource(url)` cannot set custom HTTP request headers (such as `Authorization: Bearer ...`).
To resolve this without weakening security:
- `AuthSecurityFilter` supports passing the token via URL query parameter:
  ```
  GET https://syed-apiqa-backend.onrender.com/api/runs/{id}/events?token=<signed-token>
  ```
- The token is cryptographically verified with HMAC-SHA256, and caller ownership is enforced before establishing the emitter.
- `frontend/src/app/runs/[id]/live/page.tsx` automatically appends `?token=...` from the URL or `localStorage.getItem("syed_auth_token")`.

### Render Reverse Proxy Requirements
- Render's HTTP reverse proxy supports streaming HTTP responses natively.
- In `SseEventService.java`, the emitter sets `MediaType.TEXT_EVENT_STREAM_VALUE` and emits heartbeat comments to keep connections alive through cloud proxies.

---

# 12. Database & Flyway Verification

When the backend starts up on Render, Flyway automatically applies migrations V1 through V8.

### What to Look For in Render Deployment Logs:
```
INFO  ... o.f.c.i.database.base.DatabaseType   : Database: PostgreSQL 16
INFO  ... o.f.core.internal.command.DbValidate : Successfully validated 8 migrations
INFO  ... o.f.c.i.s.JdbcTableSchema            : Creating Schema History table "public"."flyway_schema_history" ...
INFO  ... o.f.core.internal.command.DbMigrate  : Current version of schema "public": << Empty Schema >>
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "1 - initial schema"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "2 - phase1 enhancements"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "3 - phase2 enhancements"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "4 - phase3 performance and regression"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "5 - phase4 ownership"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "6 - phase5 regression findings"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "7 - phase6 run control and scheduling"
INFO  ... o.f.core.internal.command.DbMigrate  : Migrating schema "public" to version "8 - phase7 coverage and advanced testing"
INFO  ... o.f.core.internal.command.DbMigrate  : Successfully applied 8 migrations to schema "public"
```

---

# 13. Exact Production Smoke Test Checklist

Execute this smoke test against your live Vercel frontend and Render backend:

- [ ] **1. Frontend Loads**: Open `https://your-app.vercel.app` &rarr; Returns HTTP 200, renders modern dark UI.
- [ ] **2. Backend Health**:
  ```bash
  curl -i https://syed-apiqa-backend.onrender.com/api/health
  ```
  *Expect: `HTTP 200 OK` with `{"status":"UP", ...}`*.
- [ ] **3. Unauthenticated Rejection**:
  ```bash
  curl -i https://syed-apiqa-backend.onrender.com/api/runs
  ```
  *Expect: `HTTP 401 Unauthorized` with `{"error":"AUTHENTICATION_REQUIRED"}`*.
- [ ] **4. Issue Production Token**:
  ```bash
  TOKEN=$(curl -s -X POST https://syed-apiqa-backend.onrender.com/api/auth/token \
    -H "Content-Type: application/json" \
    -d '{"userId":"test-admin"}' | jq -r '.token')
  echo $TOKEN
  ```
- [ ] **5. Launch Autonomous Test Run**:
  ```bash
  RUN_ID=$(curl -s -X POST https://syed-apiqa-backend.onrender.com/api/runs \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"openapiUrl":"https://petstore.swagger.io/v2/swagger.json","environment":"STAGING"}' | jq -r '.id')
  echo $RUN_ID
  ```
- [ ] **6. Observe Live SSE Stream in Terminal**:
  ```bash
  curl -N "https://syed-apiqa-backend.onrender.com/api/runs/$RUN_ID/events?token=$TOKEN"
  ```
- [ ] **7. Verify SSRF Protection**:
  ```bash
  curl -i -X POST https://syed-apiqa-backend.onrender.com/api/runs \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"openapiUrl":"http://169.254.169.254/latest/meta-data/","environment":"STAGING"}'
  ```
  *Expect: `HTTP 400 Bad Request` with `"SSRF violation: Access to private/internal IP address is blocked"`*.
- [ ] **8. View Results in Vercel UI**: Navigate to `https://your-app.vercel.app/runs/$RUN_ID/results` &rarr; Steps display with status and latencies.
- [ ] **9. Download Vector PDF**:
  ```bash
  curl -s -H "Authorization: Bearer $TOKEN" \
    https://syed-apiqa-backend.onrender.com/api/runs/$RUN_ID/report/pdf -o audit_report.pdf
  head -c 5 audit_report.pdf
  ```
  *Expect output: `%PDF-`*.
- [ ] **10. Create Automated Schedule**:
  ```bash
  curl -s -X POST https://syed-apiqa-backend.onrender.com/api/schedules \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"Daily Petstore QA","openapiUrl":"https://petstore.swagger.io/v2/swagger.json","scheduleType":"DAILY"}'
  ```

---

# 14. Copy-Paste Production Environment Values Template

### VERCEL (Project Settings &rarr; Environment Variables)
```env
NEXT_PUBLIC_API_URL=https://syed-apiqa-backend.onrender.com
```

### RENDER (Web Service &rarr; Environment)
```env
SERVER_PORT=8080
SPRING_DATASOURCE_URL=jdbc:postgresql://<RENDER_INTERNAL_POSTGRES_HOST>:5432/syed_apiqa
SPRING_DATASOURCE_USERNAME=apiqa_user
SPRING_DATASOURCE_PASSWORD=<YOUR_RENDER_POSTGRES_PASSWORD>
SYED_AUTH_SECRET=<OUTPUT_OF_OPENSSL_RAND_BASE64_32>
SYED_ENCRYPTION_KEY=<OUTPUT_OF_OPENSSL_RAND_BASE64_32>
SYED_SECURITY_AUTH_ENABLED=true
SYED_SECURITY_ALLOWED_ORIGINS=https://<YOUR_VERCEL_PROJECT>.vercel.app
SYED_SAFETY_SSRF_PROTECTION_ENABLED=true
SYED_SAFETY_PRODUCTION_DELETE_ENABLED=false
SYED_SAFETY_MAX_CONCURRENCY=5
SYED_SAFETY_DEFAULT_TIMEOUT_SECONDS=15
SYED_SAFETY_MAX_RESPONSE_SIZE_BYTES=2097152
```

---

# 15. Deployment Failure Troubleshooting

| Symptom | Likely Cause | Exact Fix |
| :--- | :--- | :--- |
| **HTTP 502 Bad Gateway on Render** | Backend crashed on startup or wrong port configured | Check Render logs. Ensure `SERVER_PORT=8080` is set and Render port is configured to `8080`. |
| **Database Connection Failure / Timeout** | Wrong database URL format or using external URL inside Render | Ensure `SPRING_DATASOURCE_URL` begins with `jdbc:postgresql://` and uses the Render **Internal Host** (`dpg-xxxx-a`), not `localhost` or public host. |
| **Flyway Migration Failure** | Connecting to non-empty database with conflicting schema | Flyway is configured with `baseline-on-migrate: true`. If schema is corrupted, drop tables or use a fresh Render database. |
| **CORS Error in Browser Console** | `SYED_SECURITY_ALLOWED_ORIGINS` does not match Vercel domain | Add your exact Vercel URL (`https://<app>.vercel.app`) to `SYED_SECURITY_ALLOWED_ORIGINS` on Render. |
| **HTTP 401 Unauthorized on API calls** | Missing or invalid `Authorization: Bearer <token>` | Generate a token via `POST /api/auth/token` and attach it in the `Authorization` header. |
| **HTTP 403 Forbidden on API calls** | Client sent `X-User-Id` that doesn't match token identity, or cross-tenant access | Remove the custom `X-User-Id` header and let the backend derive identity from the signed Bearer token. |
| **SSE Stream Disconnects or Errors** | Browser `EventSource` cannot send headers in production | Pass the token via query parameter: `/api/runs/{id}/events?token=<signed-token>`. |
| **Frontend displays network error** | `NEXT_PUBLIC_API_URL` not set or contains trailing slash | Update `NEXT_PUBLIC_API_URL` in Vercel to `https://<backend-domain>` (no trailing slash) and trigger a redeploy. |
| **Docker Build Failure on Render** | Memory limit exceeded during Maven build | Use a Starter plan (512MB+ RAM) on Render. The multi-stage build skips tests (`-DskipTests`) during `package`. |

---

# 16. Final Deployment Readiness Verdict

# **READY TO DEPLOY**

All technical prerequisites, Docker configurations, CORS policies, query parameter SSE authentications, and environment variables have been implemented, tested, and verified against the repository codebase.
