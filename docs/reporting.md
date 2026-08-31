# Reporting Architecture — Syed API QA Agent

## 1. Overview & Report Generation Pipeline

At the conclusion of a `TestRun`, the **Reporting Engine** transforms execution records, assertion evaluations, latency metrics, and failure diagnostics into structured, professional reports.

The engine produces:
1. **Interactive Self-Contained HTML Report**: Embedded CSS/JS, readable offline, suitable for browser viewing or CI artifact archiving.
2. **Exportable PDF Report**: Formatted for executive review, technical leadership, and compliance auditing.

---

## 2. Report Sections & Anatomy

A complete audit report includes the following structured sections:

1. **Cover & Executive Summary**:
   - Run ID, target URL, environment mode, timestamp, overall test verdict (PASSED, WARNING, or FAILED).
   - High-level score: Pass rate percentage, total tests executed, test duration.
2. **Environment & Safety Metadata**:
   - Base URL, IP address resolved, security schemes detected, rate limits applied, SSRF validation status.
3. **API Inventory & Coverage**:
   - Total endpoints discovered vs. tested, breakdown by HTTP verb (`GET`, `POST`, `PUT`, `DELETE`, etc.), tag clusters.
4. **Execution Summary Matrix**:
   - Tabular grid of each endpoint with status icons, HTTP response codes, latency, and test scenario types (CRUD, edge cases).
5. **Critical Failures & Contract Violations**:
   - Detailed inspection cards for each failure:
     - Exact URL and HTTP method.
     - Expected vs. actual status code.
     - Schema validation diff (e.g. `Missing required property: email`).
     - Rule-based diagnosis (e.g. `Probable authorization misconfiguration - 403 Forbidden`).
6. **Performance & Latency Breakdown**:
   - Latency distribution chart (P50, P90, P95, P99).
   - Slowest endpoints list with threshold alerts.
7. **Resource Cleanup Audit**:
   - List of created entities and cleanup status (`CLEANED_UP` vs `CLEANUP_FAILED`).
8. **Sanitized Request & Response Evidence Appendix**:
   - Raw HTTP request headers (secrets redacted).
   - Raw HTTP request body.
   - Raw HTTP response headers.
   - Truncated response body (formatted JSON).

---

## 3. Secret Sanitization Guarantee

No report may ever expose:
- Raw Bearer tokens or API keys
- Basic auth credentials
- Passwords or secret query parameters
- Session cookies (`Set-Cookie`)

All headers and body attributes matching the secret dictionary are strictly replaced with `[REDACTED]` prior to report generation.
