# Syed API QA Agent

> **Autonomous, Deterministic API Quality Assurance for Live Deployed Backends**  
> *Zero LLM Dependency &bull; Production Safety First &bull; Modular Monolith*

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
9. **Generates** executive HTML reports and professional PDFs.

---

## 2. Zero LLM Architecture

This system has **zero external LLM dependencies** (no OpenAI, no Anthropic, no Gemini, no Ollama, no GPUs, and no AI tokens). All reasoning is governed by deterministic schema analysis, graph theory (Tarjan / Kahn algorithms), state machines, and rule-based diagnostic engines.

---

## 3. Technology Stack

- **Backend**: Java 21, Spring Boot 3.3+, Spring Data JPA, Spring Security, Maven.
- **Frontend**: Next.js 14, TypeScript, Tailwind CSS, Lucide React, Recharts.
- **Database**: PostgreSQL 16 with Flyway versioned migrations.
- **Real-Time Streaming**: Server-Sent Events (SSE) via `/api/runs/{id}/stream`.
- **Testing**: JUnit 5, Testcontainers, WireMock.
- **Containerization**: Docker, Docker Compose.

---

## 4. Getting Started

### Prerequisites
- JDK 21+
- Apache Maven 3.9+
- Node.js 20+ & npm
- Docker & Docker Compose (for containerized deployment)

### Running Locally

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
   npm install
   npm run dev
   ```

4. Open `http://localhost:3000` in your browser.

---

## 5. Architectural Documentation

Comprehensive technical specifications are located in the `docs/` directory:
- [System Architecture](docs/architecture.md)
- [Product Requirements](docs/product-requirements.md)
- [Execution Engine](docs/execution-engine.md)
- [Dependency Engine](docs/dependency-engine.md)
- [Production Safety & SSRF](docs/safety.md)
- [Failure Model & Isolation](docs/failure-model.md)
- [Performance & Latency](docs/performance.md)
- [Reporting Engine](docs/reporting.md)
- [Deployment Guide](docs/deployment.md)
- [Phase Implementation Plan](docs/phase-plan.md)
- [Architecture Decisions (ADRs)](docs/decisions.md)
- [Requirements Traceability Matrix](docs/requirements-traceability.md)
- [Edge-Case Architecture Review](docs/edge-case-review.md)
- [Phase 1 Verification Report](docs/phase-1-report.md)
- [Phase 2 Verification Report](docs/phase-2-report.md)
- [Phase 3 Verification Report](docs/phase-3-report.md)
- [Phase 4 Verification Report](docs/phase-4-report.md)
- [Phase 5 Verification Report](docs/phase-5-report.md)
- [Phase 6 Verification Report](docs/phase-6-report.md)
- [Phase 7 Verification Report](docs/phase-7-report.md)
