package com.syed.apiqa;

import com.syed.apiqa.domain.*;
import com.syed.apiqa.intelligence.DiagnosticFinding;
import com.syed.apiqa.intelligence.FailureIntelligenceService;
import com.syed.apiqa.persistence.*;
import com.syed.apiqa.reporting.PdfReportGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase4PdfAndIntelligenceTest {

    // PDF signature: 5 bytes "%PDF-" then version bytes
    private static final String PDF_MAGIC = "%PDF-";

    @Autowired private MockMvc mockMvc;
    @Autowired private TestRunRepository testRunRepository;
    @Autowired private TestCaseRepository testCaseRepository;
    @Autowired private TestStepRepository testStepRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private FailureIntelligenceService intelligenceService;
    @Autowired private PdfReportGenerator pdfReportGenerator;

    // ---------------------------------------------------------------- intelligence

    @Test
    void ruleBasedIntelligenceProducesExpectedDiagnosticCategories() {
        TestRun run = new TestRun(UUID.randomUUID().toString(), "https://example.com/v3/api-docs", EnvironmentType.STAGING);
        run.setStatus(RunStatus.COMPLETED);
        testRunRepository.save(run);

        addFailedStep(run, "Create Account", "POST", "/accounts", 401, "Missing token", StepStatus.AUTHENTICATION_ERROR);
        addFailedStep(run, "List Admin", "GET", "/admin", 403, "Forbidden", StepStatus.FAILED);
        addFailedStep(run, "Get User", "GET", "/users/{id}", 404, "Not found", StepStatus.FAILED);
        addFailedStep(run, "Create Product", "POST", "/products", 422, "Validation", StepStatus.FAILED);
        addFailedStep(run, "Delete Order", "DELETE", "/orders/{id}", 500, "Server crash", StepStatus.FAILED);

        List<DiagnosticFinding> findings = intelligenceService.analyzeRun(run);

        assertNotNull(findings);
        assertFalse(findings.isEmpty(), "At least one diagnostic finding expected");

        Map<DiagnosticFinding.Category, Integer> counts = new HashMap<>();
        for (DiagnosticFinding f : findings) {
            counts.merge(f.getCategory(), 1, Integer::sum);
            assertNotNull(f.getDiagnosis(), "Diagnosis must never be null");
            assertNotNull(f.getRemediation(), "Remediation must never be null");
        }

        assertEquals(Integer.valueOf(1), counts.get(DiagnosticFinding.Category.AUTHENTICATION_REQUIRED));
        assertEquals(Integer.valueOf(1), counts.get(DiagnosticFinding.Category.FORBIDDEN_PERMISSIONS));
        assertEquals(Integer.valueOf(1), counts.get(DiagnosticFinding.Category.RESOURCE_NOT_FOUND));
        assertEquals(Integer.valueOf(1), counts.get(DiagnosticFinding.Category.CONTRACT_VALIDATION_ERROR));
        assertEquals(Integer.valueOf(1), counts.get(DiagnosticFinding.Category.UNHANDLED_SERVER_CRASH));
    }

    @Test
    void blockedStepIsClassifiedAsDependencyBlocked() {
        TestRun run = new TestRun(UUID.randomUUID().toString(), "https://example.com/v3/api-docs", EnvironmentType.STAGING);
        run.setStatus(RunStatus.COMPLETED);
        testRunRepository.save(run);

        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName("User CRUD");
        tc.setScenarioType("CRUD_WORKFLOW");
        tc.setStatus(StepStatus.FAILED);
        testCaseRepository.save(tc);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(tc);
        step.setName("Get User");
        step.setMethod("GET");
        step.setPathTemplate("/users/{id}");
        step.setExpectedStatus(200);
        step.setStatus(StepStatus.BLOCKED);
        step.setFailureReason("BLOCKED: upstream POST /users failed");
        testStepRepository.save(step);

        List<DiagnosticFinding> findings = intelligenceService.analyzeRun(run);

        assertFalse(findings.isEmpty());
        DiagnosticFinding f = findings.get(0);
        assertEquals(DiagnosticFinding.Category.DEPENDENCY_BLOCKED, f.getCategory());
        assertTrue(f.getRemediation().toLowerCase().contains("prerequisit"));
    }

    // ---------------------------------------------------------------- PDF

    @Test
    void pdfReportIsValidAndExceedsMinSize() throws Exception {
        TestRun run = new TestRun(UUID.randomUUID().toString(), "https://example.com/v3/api-docs", EnvironmentType.STAGING);
        run.setStatus(RunStatus.COMPLETED);
        run.setCompletedAt(OffsetDateTime.now());
        testRunRepository.save(run);

        // A passed step and a failed step so the report has a matrix row and a diagnostic.
        addPassedStep(run, "Health Check", "GET", "/health", 200, 12L);
        addFailedStep(run, "Create User", "POST", "/users", 401, "Unauthorized", StepStatus.FAILED);

        byte[] pdf = pdfReportGenerator.generatePdfReport(run);

        assertNotNull(pdf);
        assertTrue(pdf.length > 2048, "PDF must exceed 2 KB, actual=" + pdf.length);
        String head = new String(pdf, 0, Math.min(8, pdf.length), StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith(PDF_MAGIC), "PDF must start with '" + PDF_MAGIC + "' got '" + head + "'");
    }

    @Test
    void pdfEndpointReturns200WithApplicationPdf() throws Exception {
        TestRun run = new TestRun(UUID.randomUUID().toString(), "https://example.com/v3/api-docs", EnvironmentType.STAGING);
        run.setStatus(RunStatus.COMPLETED);
        run.setCompletedAt(OffsetDateTime.now());
        testRunRepository.save(run);
        addPassedStep(run, "Health Check", "GET", "/health", 200, 8L);

        mockMvc.perform(get("/api/runs/{id}/report/pdf", run.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(PDF_MAGIC)));
    }

    // ---------------------------------------------------------------- helpers

    private void addPassedStep(TestRun run, String name, String method, String path, int status, long latencyMs) {
        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName(name);
        tc.setScenarioType("SINGLE_ENDPOINT");
        tc.setStatus(StepStatus.PASSED);
        testCaseRepository.save(tc);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(tc);
        step.setName(name);
        step.setMethod(method);
        step.setPathTemplate(path);
        step.setExpectedStatus(status);
        step.setStatus(StepStatus.PASSED);
        testStepRepository.save(step);

        saveExecution(step, method, "http://127.0.0.1" + path, status, latencyMs, StepStatus.PASSED);
    }

    private void addFailedStep(TestRun run, String name, String method, String path, int status,
                               String failureReason, StepStatus stepStatus) {
        TestCase tc = new TestCase();
        tc.setId(UUID.randomUUID().toString());
        tc.setTestRun(run);
        tc.setName(name);
        tc.setScenarioType("CRUD_WORKFLOW");
        tc.setStatus(StepStatus.FAILED);
        testCaseRepository.save(tc);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(tc);
        step.setName(name);
        step.setMethod(method);
        step.setPathTemplate(path);
        step.setExpectedStatus(200);
        step.setStatus(stepStatus);
        step.setFailureReason(failureReason);
        testStepRepository.save(step);

        saveExecution(step, method, "http://127.0.0.1" + path, status, 15L, rowStatus(stepStatus));
    }

    private StepStatus rowStatus(StepStatus s) {
        if (s == StepStatus.BLOCKED) return StepStatus.BLOCKED;
        if (s == StepStatus.AUTHENTICATION_ERROR) return StepStatus.AUTHENTICATION_ERROR;
        return StepStatus.FAILED;
    }

    private void saveExecution(TestStep step, String method, String url, int status, long latencyMs, StepStatus execStatus) {
        Execution exec = new Execution();
        exec.setId(UUID.randomUUID().toString());
        exec.setTestStep(step);
        exec.setMethod(method);
        exec.setRequestUrl(url);
        exec.setResponseStatus(status);
        exec.setLatencyMs(latencyMs);
        exec.setStatus(execStatus);
        exec.setStartedAt(OffsetDateTime.now());
        exec.setCompletedAt(OffsetDateTime.now());
        executionRepository.save(exec);
    }
}
