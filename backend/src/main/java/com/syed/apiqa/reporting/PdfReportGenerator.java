package com.syed.apiqa.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.intelligence.DiagnosticFinding;
import com.syed.apiqa.intelligence.FailureIntelligenceService;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.safety.SecretMasker;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Professional Vector PDF Report Generator (OpenPDF, zero-LLM).
 * Produces a multi-page, vector-quality A4 PDF with executive summary, KPI grid,
 * latency percentiles, regression audit, failure diagnostics and a sanitized evidence appendix.
 */
@Service
public class PdfReportGenerator {

    private static final Color SLATE_900 = new Color(0x0f, 0x17, 0x2a);
    private static final Color SLATE_800 = new Color(0x1e, 0x29, 0x3b);
    private static final Color DARK_SLATE = new Color(0x1e, 0x29, 0x3b);
    private static final Color SLATE_700 = new Color(0x33, 0x41, 0x55);
    private static final Color TEXT_PRIMARY = new Color(0x1e, 0x29, 0x3b);
    private static final Color TEXT_MUTED = new Color(0x64, 0x74, 0x8b);
    private static final Color EMERALD = new Color(0x10, 0xb9, 0x81);
    private static final Color ROSE = new Color(0xf4, 0x3f, 0x5e);
    private static final Color AMBER = new Color(0xf5, 0x9e, 0x0b);
    private static final Color PURPLE = new Color(0x63, 0x66, 0xf1);
    private static final Color LIGHT_BG = new Color(0xf1, 0xf5, 0xf9);
    private static final Color WHITE = Color.WHITE;

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'xxx");

    private final ApiEndpointRepository apiEndpointRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestStepRepository testStepRepository;
    private final ExecutionRepository executionRepository;
    private final AssertionResultRepository assertionResultRepository;
    private final FailureIntelligenceService intelligenceService;
    private final SecretMasker secretMasker;
    private final ObjectMapper objectMapper;

    public PdfReportGenerator(ApiEndpointRepository apiEndpointRepository,
                              TestCaseRepository testCaseRepository,
                              TestStepRepository testStepRepository,
                              ExecutionRepository executionRepository,
                              AssertionResultRepository assertionResultRepository,
                              FailureIntelligenceService intelligenceService,
                              SecretMasker secretMasker,
                              ObjectMapper objectMapper) {
        this.apiEndpointRepository = apiEndpointRepository;
        this.testCaseRepository = testCaseRepository;
        this.testStepRepository = testStepRepository;
        this.executionRepository = executionRepository;
        this.assertionResultRepository = assertionResultRepository;
        this.intelligenceService = intelligenceService;
        this.secretMasker = secretMasker;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates the full vector PDF report for a completed run and returns the raw bytes.
     */
    public byte[] generatePdfReport(TestRun testRun) {
        try {
            PdfReportData data = assemble(testRun);
            return render(testRun, data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF report for run " + testRun.getId(), e);
        }
    }

    private PdfReportData assemble(TestRun testRun) {
        List<ApiEndpoint> endpoints = apiEndpointRepository.findByTestRunId(testRun.getId());
        List<TestCase> testCases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(testRun.getId());

        List<MatrixRow> rows = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        int passedCount = 0, failedCount = 0, blockedCount = 0;

        for (TestCase tc : testCases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            for (TestStep step : steps) {
                StepStatus status = step.getStatus();
                if (status == StepStatus.PASSED) passedCount++;
                else if (status == StepStatus.FAILED || status == StepStatus.TIMEOUT
                        || status == StepStatus.NETWORK_ERROR || status == StepStatus.AUTHENTICATION_ERROR) failedCount++;
                else if (status == StepStatus.BLOCKED) blockedCount++;

                List<Execution> execs = executionRepository.findByTestStepId(step.getId());
                Execution exec = execs.isEmpty() ? null : execs.get(0);
                long latency = (exec != null && exec.getLatencyMs() != null) ? exec.getLatencyMs() : 0L;
                if (latency > 0) {
                    latencies.add(latency);
                }

                rows.add(new MatrixRow(
                        step.getMethod(),
                        step.getResolvedUrl() != null ? step.getResolvedUrl() : step.getPathTemplate(),
                        step.getExpectedStatus() != null ? step.getExpectedStatus() : -1,
                        exec != null && exec.getResponseStatus() != null ? exec.getResponseStatus() : -1,
                        latency,
                        status
                ));
            }
        }

        Collections.sort(latencies);
        long p50 = percentile(latencies, 0.50);
        long p90 = percentile(latencies, 0.90);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long min = latencies.isEmpty() ? 0 : latencies.get(0);
        long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        double avg = latencies.isEmpty() ? 0.0 : latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        avg = Math.round(avg * 100.0) / 100.0;
        int totalTests = rows.size();
        double passRate = totalTests > 0 ? (passedCount * 100.0) / totalTests : 0.0;

        String regressionJson = testRun.getRegressionSummaryJson();
        Map<String, Object> regression = new LinkedHashMap<>();
        if (regressionJson != null && !regressionJson.isBlank()) {
            try {
                regression = objectMapper.readValue(regressionJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                regression.put("raw", regressionJson);
            }
        }

        List<DiagnosticFinding> findings = intelligenceService.analyzeRun(testRun);

        return new PdfReportData(totalTests, passedCount, failedCount, blockedCount,
                passRate, endpoints.size(), p50, p90, p95, p99, min, max, avg,
                rows, findings, regression, testRun.getBaselineRunId());
    }

    private byte[] render(TestRun testRun, PdfReportData d) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 54, 54);
        PdfWriter.getInstance(document, out);
        document.open();

        renderHeader(document, testRun);
        renderExecutiveSummary(document, d);
        renderLatencyTable(document, d);
        renderRegressionSection(document, d);
        renderDiagnostics(document, d);
        renderExecutionMatrix(document, d);
        renderRedactionNotice(document);
        renderAppendix(document, testRun);

        document.close();
        return out.toByteArray();
    }

    private void renderHeader(Document document, TestRun testRun) throws DocumentException {
        PdfPTable titleTable = new PdfPTable(2);
        titleTable.setWidthPercentage(100);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.addElement(new Paragraph("Syed API QA Agent", headerFont(16, PURPLE)));
        left.addElement(new Paragraph("Autonomous Audit Report", headerFont(22, TEXT_PRIMARY)));
        left.addElement(new Paragraph("Deterministic Rule-Based Analysis · Zero LLM", footerFont(9, TEXT_MUTED)));

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.addElement(new Paragraph("ZERO-LLM ASSURANCE", tagFont(8, EMERALD)));
        right.addElement(new Paragraph("Target: " + safe(testRun.getOpenapiUrl()), bodyFont(8, TEXT_MUTED)));
        right.addElement(new Paragraph("Run ID: " + testRun.getId(), metaFont(8, TEXT_MUTED)));
        String ts = testRun.getCompletedAt() != null ? TS_FORMAT.format(testRun.getCompletedAt())
                : TS_FORMAT.format(OffsetDateTime.now());
        right.addElement(new Paragraph("Generated: " + ts, metaFont(8, TEXT_MUTED)));

        titleTable.addCell(left);
        titleTable.addCell(right);
        document.add(titleTable);

        // Thin horizontal rule
        PdfPTable ruleTable = new PdfPTable(1);
        PdfPCell ruleCell = new PdfPCell();
        ruleCell.setBorder(Rectangle.BOTTOM);
        ruleCell.setMinimumHeight(1f);
        ruleCell.setBorderColor(SLATE_700);
        ruleCell.setBorderWidth(1f);
        ruleTable.addCell(ruleCell);
        ruleTable.setWidthPercentage(100);
        document.add(ruleTable);
        document.add(new Paragraph(" ", spacerFont(4)));
    }

    private void renderExecutiveSummary(Document document, PdfReportData d) throws DocumentException {
        document.add(sectionTitle("Executive Summary & KPI"));
        PdfPTable grid = new PdfPTable(3);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(4);
        grid.setSpacingAfter(6);
        addKpi(grid, "Pass Rate", String.format("%.1f%%", d.passRate), EMERALD);
        addKpi(grid, "Total APIs", String.valueOf(d.totalEndpoints), TEXT_PRIMARY);
        addKpi(grid, "Total Tests", String.valueOf(d.totalTests), TEXT_PRIMARY);
        addKpi(grid, "Passed", String.valueOf(d.passed), EMERALD);
        addKpi(grid, "Failed / Timeout", String.valueOf(d.failed), ROSE);
        addKpi(grid, "Blocked", String.valueOf(d.blocked), AMBER);
        document.add(grid);
    }

    private void renderLatencyTable(Document document, PdfReportData d) throws DocumentException {
        document.add(sectionTitle("Performance & Latency SLA Percentiles"));
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setHeaderRows(1);
        addHeaderCell(table, "Percentile");
        addHeaderCell(table, "Value (ms)");
        addHeaderCell(table, "Percentile");
        addHeaderCell(table, "Value (ms)");
        addPair(table, "P50 (Median)", d.p50, "Min", d.min);
        addPair(table, "P90", d.p90, "Max", d.max);
        addPair(table, "P95 (SLA Target)", d.p95, "Average", d.avg);
        addPair(table, "P99 (Tail)", d.p99, "Samples", d.totalTests);
        document.add(table);
        document.add(new Paragraph(" ", spacerFont(6)));
    }

    private void renderRegressionSection(Document document, PdfReportData d) throws DocumentException {
        document.add(sectionTitle("Historical Multi-Run Regression Audit"));
        if (d.regression == null || d.regression.isEmpty()) {
            document.add(body("No prior baseline established for this target. This run forms the initial baseline.", TEXT_MUTED));
            return;
        }
        String status = str(d.regression.get("status"), "-");
        Object delta = d.regression.get("p95DeltaPercent");
        String deltaTxt = delta != null ? String.format("%+.1f%%", Double.parseDouble(delta.toString())) : "-";
        String baseRun = d.baselineRunId != null ? d.baselineRunId : str(d.regression.get("baselineRunId"), "-");
        String summary = str(d.regression.get("summary"), "");
        int contractCount = d.regression.get("contractRegressions") instanceof List
                ? ((List<?>) d.regression.get("contractRegressions")).size() : 0;

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        addHeaderCell(table, "Metric");
        addHeaderCell(table, "Value");
        addKw(table, "Regression Status", status);
        addKw(table, "Baseline Run ID", baseRun);
        addKw(table, "P95 Delta vs Baseline", deltaTxt);
        addKw(table, "Contract Regressions", String.valueOf(contractCount));
        document.add(table);
        if (!summary.isBlank()) {
            document.add(new Paragraph(" ", spacerFont(4)));
            document.add(body("Notes: " + summary, TEXT_PRIMARY));
        }
        document.add(new Paragraph(" ", spacerFont(6)));
    }

    private void renderDiagnostics(Document document, PdfReportData d) throws DocumentException {
        document.add(sectionTitle("Failure Diagnostics & Remediation"));
        if (d.findings.isEmpty()) {
            document.add(body("No failures or blocked steps detected in this run.", EMERALD));
            document.add(new Paragraph(" ", spacerFont(6)));
            return;
        }

        int maxFindingsToDisplay = Math.min(30, d.findings.size());
        if (d.findings.size() > 30) {
            document.add(body(String.format("Displaying top 30 prioritized findings out of %d total diagnostic findings. "
                    + "Full interactive failure inventory is available in the HTML Report and Results Matrix.", d.findings.size()), TEXT_MUTED));
            document.add(new Paragraph(" ", spacerFont(3)));
        }

        for (int i = 0; i < maxFindingsToDisplay; i++) {
            DiagnosticFinding f = d.findings.get(i);
            Color accent = categoryColor(f.getCategory());
            PdfPTable card = new PdfPTable(2);
            card.setWidthPercentage(100);
            card.setSpacingBefore(4);

            PdfPCell head = new PdfPCell();
            head.setColspan(2);
            head.setBackgroundColor(accent);
            head.setPadding(5);
            Paragraph headP = new Paragraph(String.format("[%s] %s %s  →  %s",
                    f.getCategory(), safe(f.getMethod()), safe(f.getPath()),
                    f.getResponseStatus() != null ? "HTTP " + f.getResponseStatus() : f.getOutcome()),
                    tagFont(8, WHITE));
            head.addElement(headP);
            card.addCell(head);

            PdfPCell diagCell = cellBody("Diagnosis", f.getDiagnosis(), accent);
            PdfPCell remCell = cellBody("Remediation", f.getRemediation(), accent);
            card.addCell(diagCell);
            card.addCell(remCell);
            document.add(card);
        }
        document.add(new Paragraph(" ", spacerFont(6)));
    }

    private void renderExecutionMatrix(Document document, PdfReportData d) throws DocumentException {
        document.add(sectionTitle("API Execution Matrix"));
        int maxRows = Math.min(100, d.rows.size());
        if (d.rows.size() > 100) {
            document.add(body(String.format("Displaying first 100 representative execution entries out of %d total test cases. "
                    + "Complete interactive matrix with filtering is available in Results Matrix.", d.rows.size()), TEXT_MUTED));
            document.add(new Paragraph(" ", spacerFont(3)));
        }

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{7f, 45f, 9f, 9f, 10f});
        table.setSpacingBefore(4);
        table.setHeaderRows(1);
        addHeaderCell(table, "Method");
        addHeaderCell(table, "Target URL / Path");
        addHeaderCell(table, "Expected");
        addHeaderCell(table, "Actual");
        addHeaderCell(table, "Verdict");

        for (int i = 0; i < maxRows; i++) {
            MatrixRow r = d.rows.get(i);
            table.addCell(cell(String.valueOf(safe(r.method)), slugFont(7, TEXT_PRIMARY)));
            table.addCell(cell(safe(r.path), slugFont(7, TEXT_MUTED)));
            table.addCell(cell(r.expected < 0 ? "-" : String.valueOf(r.expected), slugFont(7, TEXT_PRIMARY)));
            table.addCell(cell(r.actual < 0 ? "-" : String.valueOf(r.actual), slugFont(7, TEXT_PRIMARY)));
            table.addCell(cell(verdict(r.status), slugFont(7, verdictColor(r.status))));
        }
        document.add(table);
        document.add(new Paragraph(" ", spacerFont(6)));
    }

    private void renderRedactionNotice(Document document) throws DocumentException {
        document.add(sectionTitle("Confidentiality & Redaction Notice"));
        document.add(body("Bearer tokens, passwords, API keys, cookies, and other sensitive fields "
                + "were masked or redacted before persistence and are not present in this report. "
                + "The evidence appendix contains only sanitized request/response fragments.", TEXT_MUTED));
        document.add(new Paragraph(" ", spacerFont(6)));
    }

    private void renderAppendix(Document document, TestRun testRun) throws DocumentException {
        document.add(new Paragraph("Appendix A — Sanitized Evidence", headerFont(11, TEXT_PRIMARY)));
        document.add(body("Expanded request/response detail for failed steps (secrets redacted).", TEXT_MUTED));
        document.add(new Paragraph(" ", spacerFont(4)));

        List<TestCase> cases = testCaseRepository.findByTestRunIdOrderByExecutionOrderAsc(testRun.getId());
        int printed = 0;
        for (TestCase tc : cases) {
            List<TestStep> steps = testStepRepository.findByTestCaseIdOrderByStepOrderAsc(tc.getId());
            for (TestStep step : steps) {
                if (!(step.getStatus() == StepStatus.FAILED || step.getStatus() == StepStatus.TIMEOUT
                        || step.getStatus() == StepStatus.NETWORK_ERROR)) {
                    continue;
                }
                if (printed >= 8) {
                    document.add(body("… additional sanitized evidences omitted for brevity.", TEXT_MUTED));
                    return;
                }
                List<Execution> execs = executionRepository.findByTestStepId(step.getId());
                Execution exec = execs.isEmpty() ? null : execs.get(0);
                document.add(new Paragraph(String.format("%s %s — %s",
                        safe(step.getMethod()), safe(step.getPathTemplate()), step.getStatus()), tagFont(8, ROSE)));
                if (exec != null) {
                    redactAndAdd(document, "Request Body", exec.getRequestBody());
                    redactAndAdd(document, "Response Status", exec.getResponseStatus() != null ? String.valueOf(exec.getResponseStatus()) : "-");
                    redactAndAdd(document, "Response Body", exec.getResponseBody());
                }
                if (step.getFailureReason() != null && !step.getFailureReason().isBlank()) {
                    redactAndAdd(document, "Failure Reason", step.getFailureReason());
                }
                document.add(new Paragraph(" ", spacerFont(4)));
                printed++;
            }
        }
        if (printed == 0) {
            document.add(body("No failed-step evidence to display.", TEXT_MUTED));
        }
    }

    private void redactAndAdd(Document document, String label, String value) throws DocumentException {
        if (value == null || value.isBlank()) {
            return;
        }
        String sanitized = secretMasker.maskBody(value)
                .replaceAll("(?i)(password|token|secret|apiKey|api_key|access_token|cookie)\\s*[:=]\\s*\"?[^\\s,\"}]+\"?",
                        "$1=***")
                .replaceAll("Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)set-cookie\\s*:\\s*\\S+", "Set-Cookie: [REDACTED]");
        sanitized = secretMasker.maskUrl(sanitized);
        document.add(new Paragraph(label + ": ", tagFont(7, TEXT_MUTED)));
        document.add(new Paragraph(sanitized, monoFont(7, TEXT_PRIMARY)));
    }

    // ------------------------------------------------------------------ helpers

    private static void addKpi(PdfPTable grid, String label, String value, Color color) throws DocumentException {
        PdfPCell c = new PdfPCell();
        c.setPadding(8);
        c.setBackgroundColor(LIGHT_BG);
        c.setBorderColor(SLATE_700);
        Paragraph p = new Paragraph(label.toUpperCase(), kpiLabelFont());
        p.add(new Paragraph(" ", spacerFont(2)));
        p.add(new Paragraph(value, kpiValueFont(color)));
        c.addElement(p);
        grid.addCell(c);
    }

    private static void addHeaderCell(PdfPTable t, String text) throws DocumentException {
        PdfPCell c = new PdfPCell(new Phrase(text, tagFont(8, WHITE)));
        c.setBackgroundColor(DARK_SLATE);
        c.setPadding(4);
        c.setBorder(Rectangle.NO_BORDER);
        t.addCell(c);
    }

    private static void addKw(PdfPTable t, String k, String v) throws DocumentException {
        t.addCell(cell(k, slugFont(7, TEXT_MUTED)));
        t.addCell(cell(v, slugFont(7, TEXT_PRIMARY)));
    }

    private static void addPair(PdfPTable t, String a, Object av, String b, Object bv) throws DocumentException {
        t.addCell(cell(a, slugFont(7, TEXT_MUTED)));
        t.addCell(cell(String.valueOf(av), slugFont(8, TEXT_PRIMARY)));
        t.addCell(cell(b, slugFont(7, TEXT_MUTED)));
        t.addCell(cell(String.valueOf(bv), slugFont(8, TEXT_PRIMARY)));
    }

    private static PdfPCell cellBody(String label, String text, Color accent) throws DocumentException {
        PdfPCell c = new PdfPCell();
        c.setPadding(5);
        c.setBackgroundColor(LIGHT_BG);
        c.setBorderColor(SLATE_700);
        Paragraph p = new Paragraph(label, tagFont(7, accent));
        p.add(new Paragraph(" ", spacerFont(2)));
        p.add(new Paragraph(text, bodyFont(7, TEXT_PRIMARY)));
        c.addElement(p);
        return c;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(3);
        c.setBorderColor(SLATE_700);
        return c;
    }

    private static Paragraph sectionTitle(String text) throws DocumentException {
        Paragraph p = new Paragraph(text, headerFont(12, TEXT_PRIMARY));
        p.setSpacingBefore(8);
        p.setSpacingAfter(2);
        return p;
    }

    private static Paragraph body(String text, Color color) {
        return new Paragraph(text, bodyFont(9, color));
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static String verdict(StepStatus s) {
        switch (s) {
            case PASSED: return "PASSED";
            case BLOCKED: return "BLOCKED";
            case SKIPPED: return "SKIPPED";
            case TIMEOUT:
            case NETWORK_ERROR:
            case AUTHENTICATION_ERROR: return s.name();
            default: return "FAILED";
        }
    }

    private static Color verdictColor(StepStatus s) {
        switch (s) {
            case PASSED: return EMERALD;
            case BLOCKED:
            case SKIPPED: return AMBER;
            default: return ROSE;
        }
    }

    private static Color categoryColor(DiagnosticFinding.Category cat) {
        switch (cat) {
            case AUTHENTICATION_REQUIRED:
            case FORBIDDEN_PERMISSIONS: return PURPLE;
            case CONTRACT_VALIDATION_ERROR:
            case STATE_CONFLICT: return AMBER;
            case RESOURCE_NOT_FOUND:
            case DEPENDENCY_BLOCKED: return SLATE_700;
            case GATEWAY_OR_BACKEND_TIMEOUT:
            case RATE_LIMIT_EXCEEDED: return PURPLE;
            default: return ROSE;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    // fonts
    private static Font headerFont(float size, Color c) { return FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, c); }
    private static Font tagFont(float size, Color c) { return FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, c); }
    private static Font footerFont(float size, Color c) { return FontFactory.getFont(FontFactory.HELVETICA, size, c); }
    private static Font metaFont(float size, Color c) { return FontFactory.getFont(FontFactory.HELVETICA, size, c); }
    private static Font bodyFont(float size, Color c) { return FontFactory.getFont(FontFactory.HELVETICA, size, c); }
    private static Font slugFont(float size, Color c) { return FontFactory.getFont(FontFactory.HELVETICA, size, c); }
    private static Font monoFont(float size, Color c) { return FontFactory.getFont(FontFactory.COURIER, size, c); }
    private static Font kpiLabelFont() { return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, TEXT_MUTED); }
    private static Font kpiValueFont(Color c) { return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, c); }
    private static Font spacerFont(float size) { return FontFactory.getFont(FontFactory.HELVETICA, size, WHITE); }

    // ------------------------------------------------------------------ data holder

    static class PdfReportData {
        final int totalTests, passed, failed, blocked, totalEndpoints;
        final double passRate;
        final long p50, p90, p95, p99, min, max;
        final double avg;
        final List<MatrixRow> rows;
        final List<DiagnosticFinding> findings;
        final Map<String, Object> regression;
        final String baselineRunId;

        PdfReportData(int totalTests, int passed, int failed, int blocked, double passRate,
                      int totalEndpoints, long p50, long p90, long p95, long p99,
                      long min, long max, double avg, List<MatrixRow> rows,
                      List<DiagnosticFinding> findings, Map<String, Object> regression,
                      String baselineRunId) {
            this.totalTests = totalTests;
            this.passed = passed;
            this.failed = failed;
            this.blocked = blocked;
            this.passRate = passRate;
            this.totalEndpoints = totalEndpoints;
            this.p50 = p50;
            this.p90 = p90;
            this.p95 = p95;
            this.p99 = p99;
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.rows = rows;
            this.findings = findings;
            this.regression = regression;
            this.baselineRunId = baselineRunId;
        }
    }

    static class MatrixRow {
        final String method, path;
        final int expected, actual;
        final long latency;
        final StepStatus status;

        MatrixRow(String method, String path, int expected, int actual, long latency, StepStatus status) {
            this.method = method;
            this.path = path;
            this.expected = expected;
            this.actual = actual;
            this.latency = latency;
            this.status = status;
        }
    }
}
