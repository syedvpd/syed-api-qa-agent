# Deployment & Containerization Architecture — Syed API QA Agent

## 1. Overview

Syed API QA Agent is packaged as a lightweight, production-ready containerized service stack using Docker and Docker Compose. 

The architecture consists of three container services:
1. **`postgres`**: PostgreSQL 16 relational database for test runs, evidence, encrypted credentials, and configuration persistence.
2. **`backend`**: Java 21 Spring Boot modular monolith handling discovery, execution, planning, cryptographic authentication, and reporting.
3. **`frontend`**: Next.js 14+ UI providing the dashboard, real-time SSE execution stream, and interactive reports.

---

## 2. Production Security Configuration

The following environment variables MUST be configured for production deployments:

| Variable | Required | Default / Description |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | Yes | `jdbc:postgresql://postgres:5432/syed_apiqa` |
| `SPRING_DATASOURCE_USERNAME` | Yes | Database user (`apiqa_user`) |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Database password (`apiqa_password`) |
| `SYED_AUTH_SECRET` | **CRITICAL** | Master HMAC-SHA256 32+ byte secret key for signing user authentication tokens |
| `SYED_ENCRYPTION_KEY` | **CRITICAL** | Master 256-bit AES-GCM encryption key for encrypting secrets at rest in database |
| `SYED_SECURITY_AUTH_ENABLED` | Yes | Set to `true` in production to enforce Bearer token authentication |
| `SYED_SAFETY_SSRF_PROTECTION_ENABLED` | Yes | Set to `true` to block loopback, RFC 1918, and cloud metadata access |
| `SYED_SAFETY_PRODUCTION_DELETE_ENABLED`| Yes | Set to `false` to block destructive HTTP DELETE calls in production mode |
| `NEXT_PUBLIC_API_URL` | Yes | Backend URL reachable by frontend browser clients (`http://localhost:8080`) |

---

## 3. Docker Compose Architecture

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    container_name: syed-apiqa-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-syed_apiqa}
      POSTGRES_USER: ${POSTGRES_USER:-apiqa_user}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-apiqa_password}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-apiqa_user} -d ${POSTGRES_DB:-syed_apiqa}"]
      interval: 5s
      timeout: 5s
      retries: 5

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: syed-apiqa-backend
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL:-jdbc:postgresql://postgres:5432/syed_apiqa}
      SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME:-apiqa_user}
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD:-apiqa_password}
      SERVER_PORT: 8080
      SYED_SAFETY_SSRF_PROTECTION_ENABLED: "true"
      SYED_SAFETY_PRODUCTION_DELETE_ENABLED: "false"
      SYED_SECURITY_AUTH_ENABLED: "true"
      SYED_AUTH_SECRET: ${SYED_AUTH_SECRET}
      SYED_ENCRYPTION_KEY: ${SYED_ENCRYPTION_KEY}
    ports:
      - "8080:8080"

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: syed-apiqa-frontend
    restart: unless-stopped
    depends_on:
      - backend
    environment:
      NEXT_PUBLIC_API_URL: ${NEXT_PUBLIC_API_URL:-http://localhost:8080}
    ports:
      - "3000:3000"

volumes:
  pgdata:
```

---

## 4. SaaS Enterprise Identity Provider (IdP) Integration Roadmap

While the platform includes a zero-dependency stateless cryptographic authentication system (`TokenSecurityService`), enterprise SaaS deployments can seamlessly bridge to external OpenID Connect (OIDC) / OAuth2 providers:

1. **Architecture**:
   - Client applications authenticate with an IdP (Auth0, Okta, Keycloak, AWS Cognito, Google Workspace).
   - The IdP issues a signed RS256/ES256 JSON Web Token (`access_token`).
   - The client forwards `Authorization: Bearer <token>` to the Syed API QA Agent backend.
2. **Backend Verification**:
   - Configure Spring Security's `JwtDecoder` or JWKS URI (`/.well-known/jwks.json`).
   - The subject claim (`sub`) or tenant claim (`tenant_id`) maps directly to `SecurityContext.setCurrentUserId(sub)`.
   - All server-side ownership checks and tenant isolation logic remain 100% identical.
