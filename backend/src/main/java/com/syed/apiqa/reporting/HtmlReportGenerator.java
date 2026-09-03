package com.syed.apiqa.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.intelligence.DiagnosticFinding;
import com.syed.apiqa.intelligence.FailureIntelligenceService;
import com.syed.apiqa.persistence.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compiles persisted database evidence into a self-contained, responsive HTML audit report.
 * Uses real execution data only, redacts secrets, and includes executive metrics,
 * endpoint matrices, latency distributions, autonomous root-cause intelligence (API Bug vs Agent Error),
 * and raw HTTP evidence.
 */
@Service
public class HtmlReportGenerator {

    private final ApiEndpointRepository apiEndpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestStepRepository testStepRepository;
    private final ExecutionRepository executionRepository;
    private final AssertionResultRepository assertionResultRepository;
    private final ReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final com.syed.apiqa.safety.SecretMasker secretMasker;
    private final FailureIntelligenceService failureIntelligenceService;

    public HtmlReportGenerator(ApiEndpointRepository apiEndpointRepository,
                               TestCaseRepository testCaseRepository,
                               TestStepRepository testStepRepository,
                               ExecutionRepository executionRepository,
                               AssertionResultRepository assertionResultRepository,
                               ReportRepository reportRepository,
                               ObjectMapper objectMapper,
                               com.syed.apiqa.safety.SecretMasker secretMasker,
                               FailureIntelligenceService failureIntelligenceService) {
        this.apiEndpointRepository = apiEndpointRepository;
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
        this.assertionResultRepository = assertionResultRepository;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.secretMasker = secretMasker;
        this.failureIntelligenceService = failureIntelligenceService;
    }

    public Report generateAndSaveReport(TestRun testRun) {
        List<ApiEndpoint> endpoints = apiEndpointRepository.findByTestRunId(testRun.getId());
        List<TestCase> testCases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(testRun.getId());

        int totalEndpoints = endpoints.size();
        int totalTests = 0;
        int passedCount = 0;
        int failedCount = 0;
        int blockedCount = 0;
        int skippedCount = 0;
        long totalLatency = 0;
        List<Long> latencies = new ArrayList<>();

        List<Execution> allExecs = executionRepository.findByTestRunId(testRun.getId());
        Map<String, Execution> execByStepId = allExecs.stream()
                .filter(e -> e.getTestStep() != null)
                .collect(Collectors.toMap(e -> e.getTestStep().getId(), e -> e, (a, b) -> a));

        StringBuilder tableRows = new StringBuilder();
        StringBuilder evidenceCards = new StringBuilder();
        int evidenceCount = 0;
        int rowCount = 0;

        for (TestCase tc : testCases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            for (TestStep step : steps) {
                totalTests++;
                if (step.getStatus() == StepStatus.PASSED) passedCount++;
                else if (step.getStatus() == StepStatus.BLOCKED || step.getStatus() == StepStatus.REQUEST_NOT_EXECUTABLE) blockedCount++;
                else if (step.getStatus() == StepStatus.SKIPPED) skippedCount++;
                else failedCount++;

                Execution exec = execByStepId.get(step.getId());
                long latency = (exec != null && exec.getLatencyMs() != null) ? exec.getLatencyMs() : 0;
                if (latency > 0) {
                    totalLatency += latency;
                    latencies.add(latency);
                }

                if (rowCount < 500) {
                    rowCount++;
                    String actualStatus = (exec != null && exec.getResponseStatus() != null)
                            ? String.valueOf(exec.getResponseStatus())
                            : (step.getStatus() == StepStatus.BLOCKED || step.getStatus() == StepStatus.REQUEST_NOT_EXECUTABLE ? "BLOCKED" : "-");

                    String pathDisplay = (step.getResolvedUrl() != null && !step.getResolvedUrl().isBlank())
                            ? step.getResolvedUrl()
                            : (step.getPathTemplate() != null ? step.getPathTemplate() : "/");

                    tableRows.append(String.format(
                            "<tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%d ms</td><td>%s</td></tr>\n",
                            getMethodBadge(step.getMethod()),
                            escapeHtml(secretMasker.maskUrl(pathDisplay)),
                            step.getExpectedStatus() != null ? step.getExpectedStatus() : "-",
                            actualStatus,
                            latency,
                            getStatusBadge(step.getStatus())
                    ));
                }

                // Evidence card if execution occurred (capped for report performance)
                if (exec != null && evidenceCount < 60) {
                    evidenceCount++;
                    List<AssertionResult> assertions = assertionResultRepository.findByExecutionId(exec.getId());
                    StringBuilder assertionHtml = new StringBuilder();
                    for (AssertionResult ar : assertions) {
                        assertionHtml.append(String.format(
                                "<div class='text-xs py-1 %s'>&bull; [%s] %s: %s</div>",
                                ar.isPassed() ? "text-emerald-400" : "text-rose-400 font-semibold",
                                ar.getAssertionType(),
                                ar.isPassed() ? "PASSED" : "FAILED",
                                escapeHtml(ar.getMessage())
                        ));
                    }

                    String reqBody = exec.getRequestBody();
                    if (reqBody != null && reqBody.length() > 1500) {
                        reqBody = reqBody.substring(0, 1500) + "\n... [truncated for report performance]";
                    }
                    String respBody = exec.getResponseBody();
                    if (respBody != null && respBody.length() > 1500) {
                        respBody = respBody.substring(0, 1500) + "\n... [truncated for report performance]";
                    }

                    evidenceCards.append(String.format(
                            "<div class='evidence-card'>" +
                                    "<div class='evidence-header'><span>%s %s</span><span>Status: %s &bull; %d ms</span></div>" +
                                    "<div class='p-4 space-y-2 text-xs font-mono'>" +
                                    (exec.getRequestUrl() != null ? "<div><strong class='text-slate-400'>Request URL:</strong> <span class='text-slate-300'>" + escapeHtml(secretMasker.maskUrl(exec.getRequestUrl())) + "</span></div>" : "") +
                                    "<div><strong class='text-slate-400'>Assertions:</strong><br/>%s</div>" +
                                    "<div class='mt-2'><strong class='text-slate-400'>Request Headers:</strong><pre class='code-box'>%s</pre></div>" +
                                    (reqBody != null && !reqBody.isBlank() ? "<div class='mt-2'><strong class='text-slate-400'>Request Body:</strong><pre class='code-box'>" + escapeHtml(reqBody) + "</pre></div>" : "") +
                                    "<div class='mt-2'><strong class='text-slate-400'>Response Headers:</strong><pre class='code-box'>%s</pre></div>" +
                                    (respBody != null && !respBody.isBlank() ? "<div class='mt-2'><strong class='text-slate-400'>Response Body:</strong><pre class='code-box'>" + escapeHtml(respBody) + "</pre></div>" : "") +
                                    "</div></div>",
                            step.getMethod(),
                            escapeHtml(step.getName()),
                            exec.getResponseStatus() != null ? exec.getResponseStatus() : "-",
                            latency,
                            assertionHtml,
                            escapeHtml(exec.getRequestHeaders() != null ? exec.getRequestHeaders() : "{}"),
                            escapeHtml(exec.getResponseHeaders() != null ? exec.getResponseHeaders() : "{}")
                    ));
                }
            }
        }

        if (totalTests > 500) {
            tableRows.append("<tr><td colspan='6' style='text-align:center; padding:12px; color:#94a3b8; font-style:italic;'>Showing first 500 test steps in web view. Download full PDF report for the complete 1,000+ test matrix.</td></tr>");
        }

        // Generate Diagnostic Findings (Heart #5: Root Cause Intelligence)
        List<DiagnosticFinding> findings = failureIntelligenceService.analyzeRun(testRun);
        StringBuilder findingsHtml = new StringBuilder();
        if (findings.isEmpty()) {
            findingsHtml.append("<div style='color:#10b981; padding:12px; background:#064e3b22; border-radius:6px; border:1px solid #059669;'>✓ Zero failure anomalies detected across all test executions. All assertions and dependencies satisfied.</div>");
        } else {
            findingsHtml.append("<table>\n")
                    .append("<thead><tr><th>Operation</th><th>Verdict / Attribution</th><th>Confidence</th><th>Root Cause Diagnosis</th><th>Remediation & Blast Radius</th></tr></thead>\n")
                    .append("<tbody>\n");
            for (DiagnosticFinding df : findings) {
                String attributionBadge;
                if (df.getAttribution() == DiagnosticFinding.Attribution.TARGET_API) {
                    attributionBadge = "<span class='badge' style='background:#b91c1c; color:#fef2f2;'>TARGET API BUG</span>";
                } else if (df.getAttribution() == DiagnosticFinding.Attribution.QA_AGENT) {
                    attributionBadge = "<span class='badge' style='background:#d97706; color:#fffbeb;'>QA AGENT ERROR</span>";
                } else if (df.getAttribution() == DiagnosticFinding.Attribution.SPECIFICATION_MISMATCH) {
                    attributionBadge = "<span class='badge' style='background:#0284c7; color:#f0f9ff;'>SPEC MISMATCH</span>";
                } else {
                    attributionBadge = "<span class='badge' style='background:#475569; color:#f8fafc;'>" + df.getAttribution() + "</span>";
                }

                String confBadge = "<span style='font-size:10px; font-weight:700; color:#38bdf8;'>[" + df.getConfidence() + "]</span>";

                findingsHtml.append(String.format(
                        "<tr><td><strong>%s</strong><br/><code style='font-size:11px;'>%s</code></td><td>%s<br/><span style='font-size:10px; color:#94a3b8;'>%s</span></td><td>%s</td><td>%s</td><td style='font-size:11px;'><strong>Fix:</strong> %s<br/><span style='color:#94a3b8;'>%s</span></td></tr>\n",
                        escapeHtml(df.getMethod() + " " + df.getStepName()),
                        escapeHtml(df.getPath()),
                        attributionBadge,
                        df.getCategory(),
                        confBadge,
                        escapeHtml(df.getProbableRootCause()),
                        escapeHtml(df.getActionableRemediation()),
                        escapeHtml(df.getBlastRadius())
                ));
            }
            findingsHtml.append("</tbody></table>\n");
        }

        Collections.sort(latencies);
        long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.50));
        long p90 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.90));
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));
        double avgLatency = latencies.isEmpty() ? 0 : (double) totalLatency / latencies.size();

        double passRate = totalTests > 0 ? ((double) passedCount / totalTests) * 100.0 : 0.0;

        String html = "<!DOCTYPE html>\n" +
                "<html lang='en'>\n" +
                "<head>\n" +
                "  <meta charset='UTF-8'/>\n" +
                "  <title>Syed API QA Agent — Execution Report " + testRun.getId() + "</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #090d16; color: #f1f5f9; margin: 0; padding: 24px; }\n" +
                "    .container { max-width: 1200px; margin: 0 auto; }\n" +
                "    .header { border-bottom: 1px solid #1e293b; padding-bottom: 16px; margin-bottom: 24px; display: flex; justify-content: space-between; align-items: center; }\n" +
                "    .card { background: #0d1322; border: 1px solid #1e293b; border-radius: 8px; padding: 20px; margin-bottom: 24px; }\n" +
                "    .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; margin-bottom: 24px; }\n" +
                "    .kpi-box { background: #111827; border: 1px solid #1f2937; padding: 16px; border-radius: 8px; }\n" +
                "    .kpi-box .label { font-size: 11px; text-transform: uppercase; color: #94a3b8; }\n" +
                "    .kpi-box .val { font-size: 24px; font-weight: bold; margin-top: 4px; }\n" +
                "    table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }\n" +
                "    th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #1e293b; vertical-align: top; }\n" +
                "    th { background: #111827; color: #94a3b8; font-weight: 600; }\n" +
                "    .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }\n" +
                "    .badge-pass { background: #064e3b; color: #34d399; }\n" +
                "    .badge-fail { background: #4c0519; color: #fb7185; }\n" +
                "    .badge-block { background: #312e81; color: #a5b4fc; }\n" +
                "    .badge-method { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 10px; font-weight: bold; background: #1e293b; color: #38bdf8; }\n" +
                "    .evidence-card { border: 1px solid #1e293b; border-radius: 6px; margin-bottom: 12px; background: #0b0f19; overflow: hidden; }\n" +
                "    .evidence-header { background: #111827; padding: 8px 12px; display: flex; justify-content: space-between; font-weight: 600; font-size: 12px; border-bottom: 1px solid #1e293b; }\n" +
                "    .code-box { background: #030712; padding: 8px; border-radius: 4px; border: 1px solid #1f2937; overflow-x: auto; font-size: 11px; color: #94a3b8; margin: 4px 0; white-space: pre-wrap; word-break: break-all; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class='container'>\n" +
                "    <div class='header'>\n" +
                "      <div>\n" +
                "        <h1 style='margin:0; font-size:24px;'>Syed API QA Agent &bull; Audit Report</h1>\n" +
                "        <div style='color:#94a3b8; font-size:12px; margin-top:4px;'>Run ID: " + testRun.getId() + " &bull; Generated: " + OffsetDateTime.now() + "</div>\n" +
                "      </div>\n" +
                "      <div>\n" +
                "        <span class='badge " + (passRate >= 80 ? "badge-pass" : "badge-fail") + "' style='font-size:14px; padding:6px 14px;'>Pass Rate: " + String.format("%.1f%%", passRate) + "</span>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='kpi-grid'>\n" +
                "      <div class='kpi-box'><div class='label'>Endpoints</div><div class='val'>" + totalEndpoints + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Planned Tests</div><div class='val'>" + totalTests + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Passed</div><div class='val' style='color:#34d399;'>" + passedCount + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Failed</div><div class='val' style='color:#fb7185;'>" + failedCount + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Blocked / Withheld</div><div class='val' style='color:#a5b4fc;'>" + blockedCount + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Latency (P95)</div><div class='val' style='color:#38bdf8;'>" + p95 + " ms</div></div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='card'>\n" +
                "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px; display:flex; justify-content:space-between; align-items:center;'>\n" +
                "        <span>Autonomous Root-Cause Intelligence (Target API Bug vs QA Agent Error)</span>\n" +
                "        <span style='font-size:12px; color:#94a3b8;'>" + findings.size() + " Diagnostic Findings</span>\n" +
                "      </h2>\n" +
                "      " + findingsHtml.toString() + "\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='card'>\n" +
                "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px;'>Full Operation Execution Matrix</h2>\n" +
                "      <table>\n" +
                "        <thead><tr><th>Method</th><th>Target URL / Path</th><th>Expected</th><th>Actual</th><th>Latency</th><th>Verdict</th></tr></thead>\n" +
                "        <tbody>" + tableRows + "</tbody>\n" +
                "      </table>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='card'>\n" +
                "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px;'>Request & Response Evidence Appendix (Secrets Redacted)</h2>\n" +
                "      " + (evidenceCards.length() > 0 ? evidenceCards.toString() : "<div style='color:#64748b;'>No execution evidence recorded.</div>") + "\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";

        Report report = reportRepository.findByTestRunId(testRun.getId()).orElseGet(() -> {
            Report r = new Report();
            r.setId(UUID.randomUUID().toString());
            r.setTestRun(testRun);
            return r;
        });

        report.setHtmlContent(html);
        report.setPdfPath("/api/runs/" + testRun.getId() + "/report/pdf");
        report.setGeneratedAt(OffsetDateTime.now());

        Map<String, Object> summary = new HashMap<>();
        summary.put("passRate", passRate);
        summary.put("totalEndpoints", totalEndpoints);
        summary.put("totalTests", totalTests);
        summary.put("passed", passedCount);
        summary.put("failed", failedCount);
        summary.put("blocked", blockedCount);
        summary.put("p50", p50);
        summary.put("p95", p95);
        summary.put("p99", p99);
        summary.put("avgLatency", avgLatency);
        summary.put("diagnosticFindingsCount", findings.size());

        try {
            report.setSummaryJson(objectMapper.writeValueAsString(summary));
        } catch (Exception ignored) {}

        return reportRepository.save(report);
    }

    private String getStatusBadge(StepStatus status) {
        if (status == StepStatus.PASSED) return "<span class='badge badge-pass'>PASSED</span>";
        if (status == StepStatus.BLOCKED || status == StepStatus.REQUEST_NOT_EXECUTABLE) return "<span class='badge badge-block'>BLOCKED</span>";
        if (status == StepStatus.SKIPPED) return "<span class='badge' style='background:#334155; color:#94a3b8;'>SKIPPED</span>";
        return "<span class='badge badge-fail'>" + status + "</span>";
    }

    private String getMethodBadge(String method) {
        return "<span class='badge-method'>" + method + "</span>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
