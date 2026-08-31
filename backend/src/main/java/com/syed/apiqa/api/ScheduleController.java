package com.syed.apiqa.api;

import com.syed.apiqa.domain.TestRun;
import com.syed.apiqa.domain.TestSchedule;
import com.syed.apiqa.persistence.TestScheduleRepository;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import com.syed.apiqa.schedule.ScheduleExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/schedules")
@CrossOrigin(origins = "*")
public class ScheduleController {

    private final TestScheduleRepository scheduleRepository;
    private final ScheduleExecutionService scheduleExecutionService;
    private final SsrfProtectionGuard ssrfGuard;

    public ScheduleController(TestScheduleRepository scheduleRepository,
                              ScheduleExecutionService scheduleExecutionService,
                              SsrfProtectionGuard ssrfGuard) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleExecutionService = scheduleExecutionService;
        this.ssrfGuard = ssrfGuard;
    }

    @GetMapping
    public ResponseEntity<List<TestSchedule>> listSchedules(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Principal principal) {
        String requesterId = resolveRequesterId(userId, principal);
        if (requesterId != null && !requesterId.isBlank()) {
            return ResponseEntity.ok(scheduleRepository.findByOwnerIdOrderByCreatedAtDesc(requesterId));
        }
        return ResponseEntity.ok(scheduleRepository.findByOrderByCreatedAtDesc());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSchedule(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Principal principal) {
        Optional<TestSchedule> scheduleOpt = scheduleRepository.findById(id);
        if (scheduleOpt.isEmpty()) return ResponseEntity.notFound().build();

        TestSchedule schedule = scheduleOpt.get();
        if (!isAuthorized(schedule.getOwnerId(), userId, principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }
        return ResponseEntity.ok(schedule);
    }

    @PostMapping
    public ResponseEntity<?> createSchedule(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Principal principal) {
        String name = (String) body.get("name");
        String openapiUrl = (String) body.get("openapiUrl");
        String environment = (String) body.getOrDefault("environment", "STAGING");
        String scheduleTypeStr = (String) body.getOrDefault("scheduleType", "DAILY");
        String cronExpr = (String) body.get("cronExpression");
        String authType = (String) body.getOrDefault("authType", "NONE");
        String authToken = (String) body.get("authToken");

        if (name == null || name.isBlank() || openapiUrl == null || openapiUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name and openapiUrl are required."));
        }

        // Validate SSRF Protection
        try {
            ssrfGuard.validateTargetUrl(openapiUrl);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "SSRF violation: " + e.getMessage()));
        }

        TestSchedule.ScheduleType scheduleType = TestSchedule.ScheduleType.DAILY;
        try {
            scheduleType = TestSchedule.ScheduleType.valueOf(scheduleTypeStr.toUpperCase());
        } catch (Exception ignored) {}

        String requesterId = resolveRequesterId(userId, principal);
        TestSchedule schedule = new TestSchedule(requesterId, name, openapiUrl, environment, scheduleType, cronExpr);
        schedule.setAuthType(authType);
        schedule.setAuthToken(authToken);
        schedule.setNextRunAt(OffsetDateTime.now().plusHours(1));

        scheduleRepository.save(schedule);
        return ResponseEntity.status(HttpStatus.CREATED).body(schedule);
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<?> toggleSchedule(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Principal principal) {
        Optional<TestSchedule> scheduleOpt = scheduleRepository.findById(id);
        if (scheduleOpt.isEmpty()) return ResponseEntity.notFound().build();

        TestSchedule schedule = scheduleOpt.get();
        if (!isAuthorized(schedule.getOwnerId(), userId, principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        schedule.setEnabled(!schedule.isEnabled());
        schedule.setUpdatedAt(OffsetDateTime.now());
        scheduleRepository.save(schedule);
        return ResponseEntity.ok(schedule);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSchedule(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Principal principal) {
        Optional<TestSchedule> scheduleOpt = scheduleRepository.findById(id);
        if (scheduleOpt.isEmpty()) return ResponseEntity.notFound().build();

        TestSchedule schedule = scheduleOpt.get();
        if (!isAuthorized(schedule.getOwnerId(), userId, principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        scheduleRepository.delete(schedule);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<?> runScheduleNow(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            Principal principal) {
        Optional<TestSchedule> scheduleOpt = scheduleRepository.findById(id);
        if (scheduleOpt.isEmpty()) return ResponseEntity.notFound().build();

        TestSchedule schedule = scheduleOpt.get();
        if (!isAuthorized(schedule.getOwnerId(), userId, principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied."));
        }

        TestRun dispatchedRun = scheduleExecutionService.executeScheduleNow(schedule);
        return ResponseEntity.ok(Map.of(
                "message", "Scheduled run dispatched successfully",
                "runId", dispatchedRun.getId()
        ));
    }

    private String resolveRequesterId(String userId, Principal principal) {
        if (userId != null && !userId.isBlank()) return userId.trim();
        if (principal != null) return principal.getName();
        return null;
    }

    private boolean isAuthorized(String ownerId, String userId, Principal principal) {
        if (ownerId == null || ownerId.isBlank()) return true;
        String requester = resolveRequesterId(userId, principal);
        return requester != null && requester.equals(ownerId);
    }
}
