# SYED API QA AGENT — SKYLINE CREST LIVE RUNBOOK

## Objective
This runbook provides the step-by-step procedure for a human user to launch an autonomous multi-role API test run against **Skyline Crest Realty** using the **Syed API QA Agent** web interface or REST API.

---

## Prerequisites
1. **Backend Server Running:** `https://syed-api-testing-agent.onrender.com` (or `http://localhost:8080` locally).
2. **Frontend UI Running:** `https://syed-api-agent.vercel.app` (or `http://localhost:3000` locally).

---

## Step-by-Step Execution Guide

### STEP 1: Navigate to the "New Test Run" Form
- Open your browser to the Syed API QA Agent Dashboard.
- Click on **New Run** in the sidebar navigation or navigate to `/new-run`.

### STEP 2: Configure the Target OpenAPI URL & Environment
- **API Documentation or OpenAPI Specification URL Field:** You may supply any of the following URL types:
  - **ReDoc Documentation URL:** `https://skylinecrest-realty.onrender.com/api/redoc/`
  - **Swagger UI / API Docs URL:** `https://skylinecrest-realty.onrender.com/api/docs`
  - **Direct Spec URL:** `https://skylinecrest-realty.onrender.com/v3/api-docs` or `https://skylinecrest-realty.onrender.com/openapi.json`
  *Note:* The agent's `OpenApiFetchService` performs intelligent, deterministic HTML discovery (inspecting ReDoc `<redoc spec-url="...">` tags, Swagger UI Javascript configurations, and origin candidate fallbacks) to resolve the underlying OpenAPI spec automatically.
- **Environment:** Select `STAGING` (or `PRODUCTION`).

### STEP 3: Configure Multi-Role Application Credential Profiles
Click **+ Add Credential Profile** to configure all four supplied application identities:

#### Profile 1 (Admin)
- **Profile Name:** `Admin Profile`
- **Authentication Strategy:** `AUTO_DISCOVERED` (or `LOGIN_ENDPOINT`)
- **Username / Email:** `admin`
- **Password / Secret:** `[REDACTED_ADMIN_PASS]`
- **Role / Scopes:** `ADMIN`

#### Profile 2 (CRM)
- **Profile Name:** `CRM Profile`
- **Authentication Strategy:** `AUTO_DISCOVERED`
- **Username / Email:** `crm`
- **Password / Secret:** `[REDACTED_CRM_PASS]`
- **Role / Scopes:** `CRM`

#### Profile 3 (Sales)
- **Profile Name:** `Sales Profile`
- **Authentication Strategy:** `AUTO_DISCOVERED`
- **Username / Email:** `sales`
- **Password / Secret:** `[REDACTED_SALES_PASS]`
- **Role / Scopes:** `SALES`

#### Profile 4 (Customer)
- **Profile Name:** `Customer Profile`
- **Authentication Strategy:** `AUTO_DISCOVERED`
- **Username / Email:** `customer`
- **Password / Secret:** `[REDACTED_CUSTOMER_PASS]`
- **Role / Scopes:** `CUSTOMER`

---

### STEP 4: Run Preflight Check (Optional)
- Click **Preflight Verification**.
- The system calls `POST /api/runs/preflight` to validate target URL connectivity, SSRF safety, and credential profile authentication without starting a full test run.

---

### STEP 5: Start the Autonomous Test Run
- Click **Start Autonomous Test Run**.
- The system submits `POST /api/runs` and redirects you to the **Live Console** (`/runs/{runId}/live`).

---

## Live Progress & Status Classification

While the run executes in the background, the Live Console displays real-time Server-Sent Events (SSE):

| Status Indicator | Meaning | System Action |
| :--- | :--- | :--- |
| **`PASS`** | Request executed, returned valid HTTP status, and met JSON response schema contract. | Dynamic response variables extracted and bound to context. |
| **`RECOVERED_PASS`** | Request initially failed (e.g. 400/404/409), but self-healing retry succeeded with corrected data or role. | Step logged with recovery provenance. |
| **`EXPECTED_APPLICATION_RESPONSE`** | Role correctly received a documented 403 Forbidden rejection for unauthorized operations. | Recorded as legitimate access control compliance. |
| **`BLOCKED`** | Dependent step skipped because a required parent entity failed to create. | Independent parallel DAG branches continue. |
| **`REAL_FAILURE`** | Endpoint failed contract validation or returned unexpected 5xx server error after bounded retries. | Logged in root cause breakdown. |

---

## Step 6: Inspect Final Executive Report
- Once the run transitions to `COMPLETED`, navigate to `/runs/{runId}/report`.
- View KPI summary cards, topological DAG chart, endpoint coverage matrix, and export HTML/PDF audit evidence reports.
