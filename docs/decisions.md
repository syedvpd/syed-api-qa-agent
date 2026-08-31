# Architectural Decision Records (ADRs) — Syed API QA Agent

## ADR 001: Absolute Zero LLM Dependency
- **Status**: Accepted
- **Context**: Autonomous testing products often default to calling external LLM APIs (OpenAI, Claude, etc.).
- **Decision**: Syed API QA Agent will **never** rely on an external LLM, GPU cluster, or AI token API.
- **Rationale**: 
  - LLM calls introduce high latency, non-deterministic nondeterminism, token costs, hallucinated schemas, and privacy/compliance leakage of client payloads.
  - Deterministic schema-driven generation, graph algorithms, and formal rule engines provide 100% reproducible, verifiable, and millisecond-fast test cycles.

## ADR 002: Modular Monolith over Microservices for V1
- **Status**: Accepted
- **Context**: Complex systems can easily be fragmented into multiple microservices prematurely.
- **Decision**: Package the application as a single Spring Boot modular monolith with strictly decoupled packages.
- **Rationale**: Simplicity of local development, atomic database transactions across test runs and steps, zero inter-service network overhead, and trivial container deployment.

## ADR 003: Java 21 & Spring Boot 3 Stack
- **Status**: Accepted
- **Context**: High-performance HTTP client handling, robust enterprise typing, mature OpenAPI parser ecosystem.
- **Decision**: Build the core test engine with Java 21, Spring Boot 3.3+, Spring Data JPA, and Maven.
- **Rationale**: Virtual threads (Project Loom) provide lightweight concurrency for hundreds of simultaneous HTTP checks without thread pool exhaustion; `swagger-parser-v3` offers the industry-standard OpenAPI reference implementation.

## ADR 004: Server-Sent Events (SSE) for Real-Time Streaming
- **Status**: Accepted
- **Context**: Need real-time progress updates from backend execution engine to browser frontend.
- **Decision**: Utilize standard Server-Sent Events (SSE) via `SseEmitter` instead of WebSockets.
- **Rationale**: SSE is unidirectional (server -> client), operates over standard HTTP/HTTPS, effortlessly reconnects after network glitches, works cleanly across reverse proxies and firewalls without socket upgrade negotiation, and natively allows the agent to continue executing if the browser disconnects.

## ADR 005: PostgreSQL with Flyway Migrations
- **Status**: Accepted
- **Context**: Persistent storage for complex relational test runs, steps, dependencies, metrics, and JSON evidence.
- **Decision**: Use PostgreSQL 16 with native JSONB columns and Flyway versioned migrations.
- **Rationale**: Guarantees relational integrity for runs/steps while offering high-performance JSONB storage and querying for arbitrary API request/response payloads.
