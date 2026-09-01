# Syed API QA Agent

> **Autonomous, Deterministic API Quality Assurance for Live Deployed Backends**  
> *Zero LLM Dependency &bull; Production Hardened &bull; Cryptographic Security &bull; Modular Monolith*

---

## 1. Overview

**Syed API QA Agent** is an autonomous API testing platform built specifically for **live, deployed backends** (AWS, GCP, Azure, Render, Railway, VPS, or client staging/production environments). 

Given a live OpenAPI 3.x or Swagger 2.x specification URL, the agent automatically:
1. **Discovers** endpoints, schemas, authentication schemes, and path structures.
2. **Understands** relationships, parameters, and contracts.
3. **Plans** multi-step test workflows, dependency graphs, and CRUD lifecycles.
4. **Generates** deterministic, schema-compliant synthetic test data.
5. **Executes** requests with microsecond latency precision and idempotency protections.
6. **Validates** contracts, response schemas, and status codes.
7. **Isolates** failures without terminating the test run.
8. **Cleans up** test resources in reverse dependency order.
9. **Generates** executive HTML reports and professional vector PDFs.
10. **Tracks Regressions** against historical baselines with latency SLA deltas and contract drift detection.

---

## 2. Zero-LLM Architecture

This system has **zero external LLM dependencies** (no OpenAI, no Anthropic, no Gemini, no Ollama, no GPUs, and no AI tokens). All reasoning is governed by deterministic schema analysis, graph theory (Tarjan / Kahn algorithms), finite state machines, and rule-based diagnostic intelligence.

---

## 3. Production Security & Hardening

- **Cryptographic Token Authentication**: Stateless HMAC-SHA256 Bearer tokens prevent header forgery and impersonation. Client-supplied `X-User-Id` spoofing is strictly prohibited and rejected with HTTP 403.
- **AES-256-GCM Encryption-at-Rest**: Sensitive credentials (auth tokens, login credentials, session payloads) are encrypted at rest using AES-256-GCM with dynamic initialization vectors.
- **Anti-DNS Rebinding & IP Pinning**: Single DNS resolution validates IP addresses against private CIDRs, loopbacks, carrier-grade NAT, and cloud metadata (AWS/GCP/Alibaba). Outbound HTTP requests pin directly to the validated IP, eliminating TOCTOU race conditions.
- **Tenant Isolation**: Server-side ownership enforcement across all runs, schedules, audit trails, and SSE event streams. Regression baselines cannot cross tenants.
- **Production Safety**: Destructive HTTP `DELETE` operations are disabled by default in `PRODUCTION` mode.

---

## 4. Technology Stack

- **Backend**: Java 21, Spring Boot 3.3.4, Spring Data JPA, Hibernate, Maven.
- **Frontend**: Next.js 14, TypeScript, Tailwind CSS, Lucide React, Recharts.
- **Database**: PostgreSQL 16 with Flyway versioned migrations (V1 through V8).
- **Reporting**: OpenPDF 2.0.3 vector engine & self-contained responsive HTML.
- **Real-Time Streaming**: Server-Sent Events (SSE) with reconnect resilience.
- **Testing**: JUnit 5, WireMock 3.9, MockMvc.
- **Containerization**: Docker, Docker Compose.

---

## 5. Getting Started

### Prerequisites
- JDK 21+
- Apache Maven 3.9+
- Node.js 20+ & npm
- Docker & Docker Compose

### Quick Start with Docker Compose

1. Copy and configure environment variables:
   ```bash
   cp .env.example .env
   ```

2. Launch the full stack:
   ```bash
   docker-compose up -d
   ```

3. Access the services:
   - Frontend Dashboard: `http://localhost:3000`
   - Backend REST API: `http://localhost:8080`
   - Health Check: `http://localhost:8080/api/health`

### Running Locally for Development

1. **Start PostgreSQL**:
   ```bash
   docker-compose up -d postgres
   ```

2. **Run Backend**:
   ```bash
   cd backend
   mvn spring-boot:run
   ```

3. **Run Frontend**:
   ```bash
   cd frontend
   npm run dev
   ```

---

## 6. Verification & Test Suite

Run backend test suite (including all regression & security suites):
```bash
cd backend
mvn clean test
```

Run frontend production build:
```bash
cd frontend
npm run build
```
