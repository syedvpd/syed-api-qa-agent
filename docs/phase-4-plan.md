# Syed API QA Agent — Phase 4 Implementation Plan: Rule-Based Failure Intelligence & Vector PDF Reporting

## Objective
Implement Phase 4 of Syed API QA Agent:
1. **Rule-Based Failure Intelligence Engine**: Deterministic classification of failures into root causes (401 Auth, 403 Forbidden, 404 Missing/Id mismatch, 422 Validation rejection, 500 Internal crash, 504 Gateway timeout, 429 Rate limit) with actionable remediation suggestions — without requiring any external LLM.
2. **Professional Vector PDF Reporting**: High-resolution, multi-page vector PDF generation using OpenPDF with executive summaries, KPI blocks, latency percentile distributions, regression comparison, diagnostic findings, and sanitized evidence appendices.
3. **Download API & Frontend Wiring**: `GET /api/runs/{id}/report/pdf` streaming downloadable PDF binary, integrated into the Next.js reporting UI.

---

## Technical Architecture

### 1. Rule-Based Failure Intelligence Engine (`com.syed.apiqa.intelligence`)
- `FailureIntelligenceService`:
  - Analyzes each failed or blocked step across the test run:
    - Status code evaluation:
      - `401`: `AUTHENTICATION_REQUIRED` &rarr; Suggests checking Bearer token or credentials.
      - `403`: `FORBIDDEN_PERMISSIONS` &rarr; Identifies role/scope limitations.
      - `404`: `RESOURCE_NOT_FOUND` &rarr; Correlates with dependency graph to determine if upstream `CREATE` failed or variable extraction missed.
      - `409`: `STATE_CONFLICT` &rarr; Duplicate key or race condition.
      - `422` / `400`: `CONTRACT_VALIDATION_ERROR` &rarr; Pinpoints missing required attributes or malformed format.
      - `500`: `UNHANDLED_SERVER_CRASH` &rarr; Identifies backend runtime exception / unhandled error.
      - `504` / `TIMEOUT`: `GATEWAY_OR_BACKEND_TIMEOUT` &rarr; Slow database query or downstream dependency hang.
      - `429`: `RATE_LIMIT_EXCEEDED` &rarr; Recommends tuning concurrency or verifying `Retry-After`.
  - Attaches structured `DiagnosticFinding` objects to the report.

### 2. Professional Vector PDF Generation (`com.syed.apiqa.reporting.PdfReportGenerator`)
- Uses pure Java OpenPDF (`com.github.librepdf:openpdf:2.0.3`):
  - Page setup: A4 with standard margins, professional corporate palette (dark slate, emerald, rose, purple).
  - Document structure:
    - **Header**: Document title, Run ID, Target OpenAPI URL, Timestamp, Zero-LLM Assurance seal.
    - **Executive Summary & KPI Grid**: 6-box summary grid (Pass Rate %, Total APIs, Total Tests, Passed, Failed/Timeout, Blocked).
    - **Performance & Latency SLA Percentiles**: Table with P50, P90, P95, P99, Min, Max, Avg latencies.
    - **Historical Regression Audit**: Multi-run baseline comparison and delta P95 % if previous run exists.
    - **Failure Diagnostics & Remediation**: Root cause classification cards with specific actionable fix recommendations.
    - **API Execution Matrix**: Compact tabular grid of all tested endpoints with method badges, paths, response codes, latency, and pass/fail badges.
    - **Confidentiality & Redaction Notice**: Assurance statement that Bearer tokens, passwords, and cookies were masked.
- Returns `byte[]` directly for on-the-fly streaming or caches to disk (`reports/syed-audit-report-{id}.pdf`).

### 3. Controller & Frontend Integration
- In `TestRunController.java`:
  - `GET /api/runs/{id}/report/pdf`: Returns `ResponseEntity<byte[]>` with `Content-Type: application/pdf` and `Content-Disposition: attachment; filename="syed-qa-report-{id}.pdf"`.
- In `frontend/src/app/runs/[id]/report/page.tsx`:
  - Provides a dedicated "Download Official PDF Report" button that downloads the compiled PDF.

---

## Verification Plan
1. **Automated Integration Test (`Phase4PdfAndIntelligenceTest.java`)**:
   - Validates PDF generation produces a valid `%PDF-` document $> 2$ KB.
   - Validates Rule-Based Failure Intelligence produces expected diagnostic categories and recommendations on simulated 401, 403, 404, 422, and 500 responses.
   - Validates `GET /api/runs/{id}/report/pdf` returns HTTP 200 with `application/pdf`.
2. **Regression Check**:
   - `mvn test`: 100% passing across all 14 existing tests + new Phase 4 tests.
   - `npm run build`: 100% compilation on frontend routes.
