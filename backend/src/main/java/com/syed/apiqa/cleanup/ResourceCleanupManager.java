package com.syed.apiqa.cleanup;

import com.syed.apiqa.domain.CleanupRecord;
import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.execution.ExecutionContext;
import com.syed.apiqa.persistence.CleanupRecordRepository;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Automated Reverse-Dependency Resource Cleanup Manager.
 * Ensures created test entities (Users, Products, Orders) are cleanly destroyed
 * in strict reverse-topological order (child resources deleted before parent resources).
 * Strictly observes production safety policies (destructive teardown disabled in PROD).
 */
@Service
public class ResourceCleanupManager {

    private static final Logger log = LoggerFactory.getLogger(ResourceCleanupManager.class);

    private final CleanupRecordRepository cleanupRecordRepository;
    private final TestRunRepository testRunRepository;
    private final SsrfProtectionGuard ssrfGuard;
    private final HttpClient httpClient;

    public ResourceCleanupManager(CleanupRecordRepository cleanupRecordRepository,
                                  TestRunRepository testRunRepository,
                                  SsrfProtectionGuard ssrfGuard) {
        this.cleanupRecordRepository = cleanupRecordRepository;
        this.testRunRepository = testRunRepository;
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Registers a newly synthesized entity for later automated teardown.
     */
    public void recordCreatedResource(TestRun run, String resourceType, String resourceId,
                                      String deleteEndpoint, int executionOrder) {
        if (resourceId == null || resourceId.isBlank() || deleteEndpoint == null) return;

        CleanupRecord record = new CleanupRecord(
                UUID.randomUUID().toString(),
                run,
                resourceType != null ? resourceType : "entity",
                resourceId,
                deleteEndpoint,
                executionOrder
        );
        cleanupRecordRepository.save(record);
        log.info("Registered cleanup record: [{}] ID={} deleteEndpoint={}", resourceType, resourceId, deleteEndpoint);
    }

    /**
     * Executes teardown in reverse-dependency sequence.
     */
    public void executeCleanup(TestRun run, String baseUrl, ExecutionContext context, boolean isProduction, String authToken) {
        List<CleanupRecord> records = cleanupRecordRepository.findByTestRunIdOrderByExecutionOrderDesc(run.getId());
        if (records.isEmpty()) {
            run.setCleanupStatus("NOT_RUN");
            testRunRepository.save(run);
            return;
        }

        if (isProduction) {
            log.warn("TestRun {} is in PRODUCTION mode. Automated DELETE cleanup is SKIPPED for safety.", run.getId());
            for (CleanupRecord record : records) {
                record.setStatus("SKIPPED");
                record.setErrorMessage("Cleanup skipped: Destructive DELETE operations disabled in PRODUCTION mode");
                record.setCleanedAt(OffsetDateTime.now());
                cleanupRecordRepository.save(record);
            }
            run.setCleanupStatus("SKIPPED");
            testRunRepository.save(run);
            return;
        }

        log.info("Initiating reverse-topological teardown for TestRun {}. Total resources to clean: {}", run.getId(), records.size());

        boolean hasFailures = false;
        for (CleanupRecord record : records) {
            try {
                // Resolve target URL (e.g. /users/{id} -> /users/usr_999)
                String path = record.getDeleteEndpoint()
                        .replace("{id}", record.getResourceId())
                        .replace("{userId}", record.getResourceId())
                        .replace("{productId}", record.getResourceId())
                        .replace("{orderId}", record.getResourceId());

                // Fallback to regex resolution if template contains other curly braces
                ExecutionContext.ResolutionResult resolved = context.resolve(path);
                String relativePath = resolved.getResolvedContent();
                String targetUrl = baseUrl + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);

                ssrfGuard.validateTargetUrl(targetUrl);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "Syed-API-QA-Agent-Cleanup/1.0")
                        .DELETE();

                if (authToken != null && !authToken.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + authToken.trim());
                }

                HttpResponse<Void> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();

                // 2xx success or 404 (resource already removed or transient) are considered clean
                if ((status >= 200 && status < 300) || status == 404) {
                    record.setStatus("COMPLETED");
                    record.setErrorMessage(null);
                    log.info("Cleaned up resource [{}] ID={} status={}", record.getResourceType(), record.getResourceId(), status);
                } else {
                    hasFailures = true;
                    record.setStatus("FAILED");
                    record.setErrorMessage("DELETE returned unexpected HTTP " + status);
                    log.warn("Cleanup failed for [{}] ID={}: HTTP {}", record.getResourceType(), record.getResourceId(), status);
                }

            } catch (Exception e) {
                hasFailures = true;
                record.setStatus("FAILED");
                record.setErrorMessage("Teardown error: " + e.getMessage());
                log.error("Exception cleaning up [{}] ID={}: {}", record.getResourceType(), record.getResourceId(), e.getMessage());
            } finally {
                record.setCleanedAt(OffsetDateTime.now());
                cleanupRecordRepository.save(record);
            }
        }

        run.setCleanupStatus(hasFailures ? "PARTIAL" : "EXECUTED");
        testRunRepository.save(run);
    }
}
