package com.syed.apiqa.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.*;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compiles persisted database evidence into a self-contained, responsive HTML audit report.
 * Uses real execution data only, redacts secrets, and includes executive metrics,
 * endpoint matrices, latency distributions, and raw HTTP evidence.
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

    public HtmlReportGenerator(ApiEndpointRepository apiEndpointRepository,
                               TestCaseRepository testCaseRepository,
                               TestStepRepository testStepRepository,
                               ExecutionRepository executionRepository,
                               AssertionResultRepository assertionResultRepository,
                               ReportRepository reportRepository,
                               ObjectMapper objectMapper,
                               com.syed.apiqa.safety.SecretMasker secretMasker) {
        this.apiEndpointRepository = apiEndpointRepository;
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
        this.assertionResultRepository = assertionResultRepository;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.secretMasker = secretMasker;
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
                else if (step.getStatus() == StepStatus.FAILED || step.getStatus() == StepStatus.TIMEOUT || step.getStatus() == StepStatus.NETWORK_ERROR) failedCount++;
                else if (step.getStatus() == StepStatus.BLOCKED) blockedCount++;
                else if (step.getStatus() == StepStatus.SKIPPED) skippedCount++;

                Execution exec = execByStepId.get(step.getId());

                long latency = (exec != null && exec.getLatencyMs() != null) ? exec.getLatencyMs() : 0;
                if (latency > 0) {
                    latencies.add(latency);
                    totalLatency += latency;
                }

                if (rowCount < 500) {
                    rowCount++;
                    String statusBadge = getStatusBadge(step.getStatus());
                    String methodBadge = getMethodBadge(step.getMethod());

                    tableRows.append(String.format(
                            "<tr><td class='font-mono'>%s</td><td class='font-mono text-sm'>%s</td><td>%s</td><td>%s</td><td>%d ms</td><td>%s</td></tr>",
                            methodBadge,
                            escapeHtml(secretMasker.maskUrl(step.getResolvedUrl() != null ? step.getResolvedUrl() : step.getPathTemplate())),
                            step.getExpectedStatus() != null ? step.getExpectedStatus() : "-",
                            (exec != null && exec.getResponseStatus() != null) ? exec.getResponseStatus() : "-",
                            latency,
                            statusBadge
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
                "    th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #1e293b; }\n" +
                "    th { background: #111827; color: #94a3b8; font-weight: 600; }\n" +
                "    .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }\n" +
                "    .badge-pass { background: rgba(16, 185, 129, 0.2); color: #34d399; border: 1px solid rgba(16, 185, 129, 0.4); }\n" +
                "    .badge-fail { background: rgba(244, 63, 94, 0.2); color: #fb7185; border: 1px solid rgba(244, 63, 94, 0.4); }\n" +
                "    .badge-block { background: rgba(245, 158, 11, 0.2); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.4); }\n" +
                "    .badge-method { background: #1e293b; color: #38bdf8; border: 1px solid #334155; font-size: 11px; font-weight: bold; padding: 2px 6px; border-radius: 4px; }\n" +
                "    .evidence-card { background: #0f172a; border: 1px solid #1e293b; border-radius: 6px; margin-bottom: 16px; overflow: hidden; }\n" +
                "    .evidence-header { background: #1e293b; padding: 8px 16px; font-weight: 600; font-size: 12px; display: flex; justify-content: space-between; }\n" +
                "    .code-box { background: #020617; padding: 8px; border-radius: 4px; overflow-x: auto; color: #cbd5e1; max-height: 200px; font-size: 11px; margin: 4px 0; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class='container'>\n" +
                "    <div class='header'>\n" +
                "      <div>\n" +
                "        <h1 style='margin:0; font-size:24px;'>Syed API QA Agent &bull; Audit Report</h1>\n" +
                "        <div style='color:#94a3b8; font-size:12px; margin-top:4px;'>Target: " + escapeHtml(testRun.getOpenapiUrl()) + "</div>\n" +
                "      </div>\n" +
                "      <div style='text-align:right;'>\n" +
                "        <a href='/api/runs/" + testRun.getId() + "/report/pdf' target='_blank' style='background:#6366f122; color:#818cf8; border:1px solid #6366f144; text-decoration:none; padding:4px 12px; border-radius:4px; font-size:11px; font-weight:600; margin-right:8px; display:inline-block;'>DOWNLOAD PDF REPORT</a>\n" +
                "        <span class='badge' style='background:#10b98122; color:#10b981; border:1px solid #10b98144;'>ZERO-LLM DETERMINISTIC AUDIT</span>\n" +
                "        <div style='color:#64748b; font-size:11px; margin-top:4px;'>Run ID: " + testRun.getId() + "</div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='kpi-grid'>\n" +
                "      <div class='kpi-box'><div class='label'>Pass Rate</div><div class='val' style='color:#10b981;'>" + String.format("%.1f%%", passRate) + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>API QA Coverage</div><div class='val' style='color:#38bdf8;'>" + (testRun.getCoverageScore() != null ? String.format("%.1f%%", testRun.getCoverageScore()) : "N/A") + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Total APIs</div><div class='val'>" + totalEndpoints + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Total Tests</div><div class='val'>" + totalTests + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Passed</div><div class='val' style='color:#10b981;'>" + passedCount + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Failed / Timeout</div><div class='val' style='color:#f43f5e;'>" + failedCount + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>Blocked Tests</div><div class='val' style='color:#f59e0b;'>" + blockedCount + "</div></div>\n" +
                "      <div class='kpi-box'><div class='label'>P95 Latency</div><div class='val'>" + p95 + " ms</div></div>\n" +
                "    </div>\n" +
                "\n" +
                (testRun.getCoverageSummaryJson() != null && !testRun.getCoverageSummaryJson().isBlank() ?
                        "    <div class='card' style='border-left: 4px solid #10b981;'>\n" +
                        "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px;'>API QA Coverage & Endpoint Classification</h2>\n" +
                        "      <pre class='code-box' style='max-height:200px;'>" + escapeHtml(testRun.getCoverageSummaryJson()) + "</pre>\n" +
                        "    </div>\n" : "") +
                "\n" +
                "    <div class='card'>\n" +
                "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px;'>Latency Distribution & Percentiles</h2>\n" +
                "      <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap:12px; font-family:monospace; margin-top:12px;'>\n" +
                "        <div style='background:#111827; padding:12px; border-radius:6px;'><span style='color:#94a3b8; font-size:11px;'>P50 (Median)</span><br/><strong style='font-size:18px; color:#38bdf8;'>" + p50 + " ms</strong></div>\n" +
                "        <div style='background:#111827; padding:12px; border-radius:6px;'><span style='color:#94a3b8; font-size:11px;'>P90</span><br/><strong style='font-size:18px; color:#38bdf8;'>" + p90 + " ms</strong></div>\n" +
                "        <div style='background:#111827; padding:12px; border-radius:6px;'><span style='color:#94a3b8; font-size:11px;'>P95 (SLA Target)</span><br/><strong style='font-size:18px; color:#a855f7;'>" + p95 + " ms</strong></div>\n" +
                "        <div style='background:#111827; padding:12px; border-radius:6px;'><span style='color:#94a3b8; font-size:11px;'>P99 (Tail)</span><br/><strong style='font-size:18px; color:#ec4899;'>" + p99 + " ms</strong></div>\n" +
                "        <div style='background:#111827; padding:12px; border-radius:6px;'><span style='color:#94a3b8; font-size:11px;'>Average</span><br/><strong style='font-size:18px; color:#e2e8f0;'>" + String.format("%.1f", avgLatency) + " ms</strong></div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                (testRun.getRegressionSummaryJson() != null && !testRun.getRegressionSummaryJson().isBlank() ?
                        "    <div class='card' style='border-left: 4px solid #38bdf8;'>\n" +
                        "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px;'>Historical Multi-Run Regression Audit</h2>\n" +
                        "      <pre class='code-box' style='max-height:250px;'>" + escapeHtml(testRun.getRegressionSummaryJson()) + "</pre>\n" +
                        "    </div>\n" : "") +
                "\n" +
                "    <div class='card'>\n" +
                "      <h2 style='margin-top:0; font-size:16px; border-bottom:1px solid #1e293b; padding-bottom:8px;'>Endpoint Execution Matrix</h2>\n" +
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

        try {
            report.setSummaryJson(objectMapper.writeValueAsString(summary));
        } catch (Exception ignored) {}

        return reportRepository.save(report);
    }

    private String getStatusBadge(StepStatus status) {
        if (status == StepStatus.PASSED) return "<span class='badge badge-pass'>PASSED</span>";
        if (status == StepStatus.BLOCKED) return "<span class='badge badge-block'>BLOCKED</span>";
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
