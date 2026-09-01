# Syed API QA Agent

> **Autonomous, Deterministic API Quality Assurance for Live Deployed Backends**  
> *Zero LLM Dependency &bull; Production Hardened &bull; Cryptographic Security &bull; Modular Monolith*

---

## 1. Overview & Core Philosophy

**Syed API QA Agent** is an autonomous API quality assurance and verification platform engineered specifically for **live, deployed backends** (AWS, GCP, Azure, Kubernetes, Render, Railway, bare-metal VPS, or client staging/production environments).

Given an OpenAPI 3.x or Swagger 2.x specification URL, the agent autonomously executes a complete quality assurance lifecycle:
1. **Discovers**: Ingests OpenAPI specifications via SSRF-hardened outbound dispatchers.
2. **Understands**: Constructs dependency graphs and topological execution orders (Tarjan / Kahn algorithms).
3. **Generates**: Produces deterministic, schema-compliant synthetic test payloads with microsecond seed reproducibility.
4. **Executes**: Dispatches HTTP requests with bounded retries, microsecond response timing, and rate-limit backoffs.
5. **Validates**: Checks status codes, response headers, JSON schemas, and business invariants.
6. **Isolates Failures**: Upstream failures mark downstream steps as `BLOCKED` while allowing independent endpoints to execute.
7. **Cleans Up**: Restores state via reverse-topological `DELETE` teardown operations.
8. **Analyzes Regressions**: Compares latency percentiles (P50, P90, P95, P99) and detects contract drift against baseline runs.
9. **Reports**: Compiles standalone executive HTML reports and OpenPDF vector PDF documents.

---

## 2. Zero-LLM Architecture Guarantee

This system contains **ZERO external AI or LLM dependencies**:
- **0** OpenAI, Anthropic, Gemini, Ollama, LangChain, or HuggingFace libraries.
- **0** External inference API calls or third-party cloud AI keys.
- **0** Nondeterministic hallucinations.
- **100% Deterministic Code**: All reasoning is governed by OpenAPI AST parsers, topological directed acyclic graphs (DAGs), deterministic pseudo-random generators, and rule-based diagnostic engines.

---

## 3. Production Security Model

| Security Dimension | Implementation & Hardening |
| :--- | :--- |
| **Authentication** | Stateless HMAC-SHA256 Bearer tokens (`syed_sec_v1.<payload>.<signature>`) preventing header forgery. Client-supplied `X-User-Id` spoofing is rejected with HTTP 403 Forbidden. |
| **Multi-Tenancy** | Strict server-side `ownerId` verification across all runs, steps, reports, PDF downloads, schedules, and SSE event feeds. |
| **Encryption-at-Rest** | Sensitive columns (`authToken`, `authLoginPayload`, `authCredentials`) are encrypted in PostgreSQL using AES-256-GCM with dynamic 12-byte IVs. Ciphertexts are prefixed with `ENC:`. |
| **Response Redaction** | Sensitive credentials are excluded via `@JsonIgnore` and redacted from logs, HTML reports, and vector PDFs using `SecretMasker`. |
| **SSRF Anti-DNS Rebinding** | `SsrfProtectionGuard` resolves hostnames once, blocks private CIDRs, CGNAT, loopbacks, and cloud metadata (AWS/GCP/Alibaba), and binds outbound sockets directly to the pinned IP with virtual `Host` headers and TLS SNI verification. |
| **Production Safety** | Destructive HTTP `DELETE` operations are disabled in `PRODUCTION` mode. POST/PUT/PATCH requests are never retried automatically. GET requests are bounded to 2 attempts. Responses >2MB are safely truncated. |

---

## 4. Repository Structure

```
.
├── backend/                             # Spring Boot 3.3.4 (Java 21) Modular Monolith
│   ├── src/main/java/com/syed/apiqa/
│   │   ├── api/                         # REST Controllers (Runs, Schedules, Auth, Health)
│   │   ├── auth/                        # Dynamic Auth & Token Refresh Engine
│   │   ├── cleanup/                     # Teardown & Resource Cleanup Manager
│   │   ├── config/                      # WebMvc, Async, and ThreadPool Configuration
│   │   ├── discovery/                   # OpenAPI Fetch & Schema Parser
│   │   ├── domain/                      # JPA Entities & Enumerations
│   │   ├── execution/                   # HttpExecutionEngine & Step Assertions
│   │   ├── generation/                  # Deterministic Data Generator
│   │   ├── persistence/                 # Spring Data Repositories
│   │   ├── regression/                  # Regression Analysis & Contract Drift
│   │   ├── reporting/                   # HTML Report & OpenPDF Vector Engine
│   │   ├── run/                         # RunManager, Concurrency & SSE Event Service
│   │   ├── safety/                      # SSRF Guard, Secret Masker, AES-256-GCM Converter
│   │   ├── schedule/                    # Cron Scheduler & Automation Service
│   │   └── security/                    # TokenSecurityService & AuthSecurityFilter
│   └── src/main/resources/
│       ├── application.yml              # Main application configuration
│       └── db/migration/                # Flyway migrations V1 through V8
├── frontend/                            # Next.js 14 (App Router) + Tailwind CSS
│   ├── src/app/
│   │   ├── dashboard/                   # Global execution dashboard
│   │   ├── new-run/                     # Test run configuration & dispatch
│   │   ├── runs/[id]/                   # Run details overview
│   │   ├── runs/[id]/live/              # Real-time SSE execution stream
│   │   ├── runs/[id]/results/           # Test steps & failure diagnostics
│   │   ├── runs/[id]/report/            # HTML viewer & PDF report download
│   │   ├── runs/[id]/regression/        # Run-to-run regression analysis
│   │   └── schedules/                   # Automated schedule management
│   └── src/lib/api.ts                   # Centralized dynamic API base URL resolver
├── docker-compose.yml                   # Multi-container production compose definition
├── .env.example                         # Environment configuration template
└── docs/                                # Architecture, security audit & release reports
```

---

## 5. Environment Configuration

Copy `.env.example` to `.env` before running:
```bash
cp .env.example .env
```

### Critical Production Environment Variables

| Variable | Description | Production Requirement |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://<host>:5432/<database>` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL database user | Dedicated non-root user |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL database password | Strong random password |
| `SYED_AUTH_SECRET` | HMAC-SHA256 token signing key | Random 256-bit secret (`openssl rand -base64 32`) |
| `SYED_ENCRYPTION_KEY` | AES-256-GCM column encryption key | Random 256-bit key (`openssl rand -base64 32`) |
| `NEXT_PUBLIC_API_URL` | Frontend-to-Backend URL | Public API domain (e.g., `https://api.qa.domain.com`) |
| `SYED_SECURITY_AUTH_ENABLED`| Enforce cryptographic auth | Set to `true` |
| `SYED_SAFETY_PRODUCTION_DELETE_ENABLED` | Suppress destructive DELETE in prod | Set to `false` |

---

## 6. Step-by-Step Developer Quickstart

### Step 1: Clone Repository
```bash
git clone https://github.com/syedvpd/syed-api-qa-agent.git
cd syed-api-qa-agent
```

### Step 2: Configure Environment
```bash
cp .env.example .env
```

### Step 3: Start PostgreSQL
```bash
docker compose up -d postgres
```

### Step 4: Run Backend
Flyway will automatically execute database migrations V1 through V8 on startup:
```bash
cd backend
mvn spring-boot:run
```
*Backend listens on `http://localhost:8080`*.

### Step 5: Run Frontend
```bash
cd frontend
npm install
npm run dev
```
*Frontend listens on `http://localhost:3000`*.

### Step 6: Authenticate & Obtain Token
Generate a cryptographically signed Bearer token:
```bash
curl -X POST http://localhost:8080/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "dev-user-001"}'
```
Response:
```json
{
  "token": "syed_sec_v1.ZGV2LXVzZXItMDAxOjE3MjUyODAwMDA.sig...",
  "tokenType": "Bearer",
  "userId": "dev-user-001",
  "expiresAt": "2026-09-02T12:00:00Z"
}
```

### Step 7: Launch Your First Autonomous API Test Run
```bash
curl -X POST http://localhost:8080/api/runs \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "openapiUrl": "https://petstore.swagger.io/v2/swagger.json",
    "environment": "STAGING"
  }'
```

---

## 7. REST API Reference

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/auth/token` | `POST` | Issues signed HMAC-SHA256 authentication tokens |
| `/api/health` | `GET` | Service status, version, and component health |
| `/api/runs` | `GET` | List test runs scoped to the authenticated tenant |
| `/api/runs` | `POST` | Initialize and execute an autonomous test run |
| `/api/runs/{id}` | `GET` | Retrieve test run details and current status |
| `/api/runs/{id}/endpoints` | `GET` | Retrieve discovered endpoints and dependencies |
| `/api/runs/{id}/cases` | `GET` | Retrieve test steps, status, latency, and assertions |
| `/api/runs/{id}/events` | `GET` | Real-time Server-Sent Events (SSE) progress stream |
| `/api/runs/{id}/report` | `GET` | Standalone responsive HTML executive report |
| `/api/runs/{id}/report/pdf` | `GET` | Download OpenPDF vector audit report |
| `/api/runs/{id}/regression` | `GET` | Latency regressions, contract drift, and delta analysis |
| `/api/runs/{id}/regression/compare` | `POST` | Compare run against a specific baseline run |
| `/api/runs/{id}/pause` | `POST` | Pause an active execution |
| `/api/runs/{id}/resume` | `POST` | Resume a paused execution |
| `/api/runs/{id}/cancel` | `POST` | Cancel active test run with audit record |
| `/api/schedules` | `GET` | List recurring test schedules for the authenticated tenant |
| `/api/schedules` | `POST` | Create a recurring schedule (DAILY, WEEKLY, CRON) |
| `/api/schedules/{id}/toggle` | `PATCH` | Enable or disable automated execution |
| `/api/schedules/{id}/run-now` | `POST` | Dispatch scheduled test run immediately |
| `/api/schedules/{id}` | `DELETE` | Delete a scheduled test |

---

## 8. Automated Testing & Verification

Execute the complete backend automated test suite (including all security regression suites):
```bash
cd backend
mvn clean test
```
*Current test suite: **60 / 60 tests passing**, 0 failures, 0 errors*.

Compile and verify the Next.js frontend production bundle:
```bash
cd frontend
npm run build
```
*All 10 routes compiled with zero linting or TypeScript errors in standalone container mode*.

---

## 9. Production Container Deployment

Build and run the entire stack using Docker Compose:
```bash
docker compose build --no-cache
docker compose up -d
```
The stack launches:
- `syed-apiqa-postgres`: PostgreSQL 16 Alpine with persistent volume `pgdata`.
- `syed-apiqa-backend`: Eclipse Temurin 21 JRE container.
- `syed-apiqa-frontend`: Node.js 22 Alpine standalone production server.
