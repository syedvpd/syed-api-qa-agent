# Product Requirements Document (PRD) — Syed API QA Agent

## 1. Executive Summary & Value Proposition

**Syed API QA Agent** is an autonomous, non-LLM API testing platform engineered specifically for **live, deployed backends** (e.g., Render, Railway, AWS, Azure, GCP, VPS, client staging, or production environments). 

Rather than requiring human QA engineers or developers to manually hand-craft Postman collections or write boilerplate integration scripts, Syed API QA Agent takes an OpenAPI/Swagger URL as input, completely maps the backend, synthesizes valid test data, executes complex stateful workflows (such as CRUD chains), evaluates contracts, isolates failures, and produces an executive-ready HTML and PDF audit report.

---

## 2. Target Users & Personas

1. **Backend Engineers**: Need to verify newly deployed services on staging or production immediately after CI/CD completion.
2. **QA Engineers / Test Leads**: Require rigorous, repeatable regression suites and automated contract compliance reports without maintaining brittle script repositories.
3. **Startups & Engineering Teams**: Testing client APIs or third-party partner integrations before going live.
4. **Security & Compliance Auditors**: Verifying deployed services against API schema drift, unauthenticated endpoint exposure, and HTTP spec violations.

---

## 3. Critical Product Principles

### A. Live Deployed Backend Target (Not Local Code Linting)
- The system must test over HTTP/HTTPS against live network endpoints.
- It does not parse source code files, ASTs, or git repos; it tests deployed behavior against the published API contract.

### B. Absolute Zero LLM Dependency
- **No external AI APIs** (No OpenAI, Anthropic, Gemini, DeepSeek, etc.).
- **No local AI runners** (No Ollama, Llama.cpp, or GPU workloads).
- **No AI API keys** required to deploy or operate Syed API QA Agent.
- All intelligence is implemented via:
  - Deterministic schema parsing and generation algorithms
  - Graph-based dependency resolution and cycle detection
  - State machine workflow orchestration
  - Heuristic matching rules for variable extraction
  - Deterministic statistical latency analysis

### C. Walk-Away Autonomous Operation
- Users configure the run, press "Start Test", and can walk away.
- Disconnecting from the web UI does not abort test execution.
- Reconnecting to `/runs/{id}/live` seamlessly streams live progress.

---

## 4. Functional Requirements

### 4.1 OpenAPI & Swagger Discovery
- **Input Formats**: OpenAPI 3.0.x, 3.1.x, and Swagger 2.0 (JSON or YAML).
- **Extraction**: HTTP methods, path templates, tags, operation IDs, summaries, request body schemas, parameters (path, query, header), response codes, response schemas, and security schemes (Bearer, Basic, API Key).
- **Validation**: Schema syntax validation and unreachable/malformed path filtering.

### 4.2 Deterministic Test Data Generation
- Supported primitive types: `string`, `integer`, `number`, `boolean`.
- Formats: `uuid`, `email`, `date`, `date-time`, `uri`, `ipv4`, `ipv6`, `hostname`.
- Constraints: `minLength`, `maxLength`, `pattern` (regex-compliant generation), `minimum`, `maximum`, `multipleOf`, `enum`, `default`, `example`.
- Complex structures: Nested `object` (with `required` properties) and `array` (with `minItems`, `maxItems`, `uniqueItems`).
- Randomness: Seeded deterministic pseudo-random generator to guarantee reproducible test runs when re-run with identical seeds.

### 4.3 Dependency Engine & Context System
- Automatic detection of parent-child relationships (e.g., `POST /users` returns an ID needed for `GET /users/{id}`).
- Variable interpolation syntax: `{{user.id}}`, `{{tenant.id}}`.
- Confidence scoring: `HIGH` (direct schema type/name match), `MEDIUM` (heuristic field name match), `LOW` (general type match).
- Cycle detection to avoid infinite loops during test tree construction.

### 4.4 Execution & Assertion Engine
- HTTP Execution: Spring `RestClient` with configurable timeouts and connection pooling.
- Safe retries: GET requests are idempotent and retryable; POST requests are **never** blindly retried on network timeout.
- Assertions:
  - Expected HTTP status code matches contract.
  - Response headers comply with specifications.
  - JSON schema conformance of response body.
  - Required fields are present and not null.
  - Variable extraction from successful responses into the run context.

### 4.5 Production Safety & Sandboxing
- Strict SSRF protection preventing requests to `localhost`, loopback (`127.0.0.1/8`), private networks (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), and cloud metadata services (`169.254.169.254`).
- Environment profiles: In **Production** mode, destructive verbs (`DELETE`, `PUT`, `PATCH`) are disabled unless explicitly authorized.
- Secret masking: Authorization headers, tokens, and sensitive query parameters are masked (`****`) in execution logs, database records, and reports.

### 4.6 Reporting
- Self-contained HTML report with executive pass/fail metrics, endpoint coverage matrix, detailed request/response evidence, and latency charts.
- Exportable PDF report for management and compliance deliverables.
