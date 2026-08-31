package com.syed.apiqa.schedule;

import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.TestRunRepository;
import com.syed.apiqa.persistence.TestScheduleRepository;
import com.syed.apiqa.run.RunManager;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Production Scheduler Engine managing recurring autonomous API test execution.
 * Respects SSRF protection, tenant isolation, bounded concurrency, and safe resource limits.
 */
@Service
public class ScheduleExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionService.class);

    private final TestScheduleRepository testScheduleRepository;
    private final TestRunRepository testRunRepository;
    private final RunManager runManager;
    private final SsrfProtectionGuard ssrfGuard;

    public ScheduleExecutionService(TestScheduleRepository testScheduleRepository,
                                    TestRunRepository testRunRepository,
                                    RunManager runManager,
                                    SsrfProtectionGuard ssrfGuard) {
        this.testScheduleRepository = testScheduleRepository;
        this.testRunRepository = testRunRepository;
        this.runManager = runManager;
        this.ssrfGuard = ssrfGuard;
    }

    /**
     * Polls active schedules periodically and launches due runs.
     */
    @Scheduled(fixedDelay = 15000)
    @Transactional
    public void processDueSchedules() {
        OffsetDateTime now = OffsetDateTime.now();
        List<TestSchedule> enabledSchedules = testScheduleRepository.findByEnabledTrue();

        for (TestSchedule schedule : enabledSchedules) {
            if (schedule.getNextRunAt() == null || schedule.getNextRunAt().isBefore(now)) {
                try {
                    executeScheduleNow(schedule);
                } catch (Exception e) {
                    log.error("Failed to execute scheduled job {}: {}", schedule.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Dispatches a scheduled run immediately and computes next due timestamp.
     */
    public TestRun executeScheduleNow(TestSchedule schedule) {
        // Enforce SSRF protection before dispatch
        ssrfGuard.validateTargetUrl(schedule.getOpenapiUrl());

        EnvironmentType envType = EnvironmentType.STAGING;
        try {
            envType = EnvironmentType.valueOf(schedule.getEnvironment().toUpperCase());
        } catch (Exception ignored) {}

        TestRun run = new TestRun(UUID.randomUUID().toString(), schedule.getOpenapiUrl(), envType);
        run.setOwnerId(schedule.getOwnerId());
        testRunRepository.save(run);

        OffsetDateTime now = OffsetDateTime.now();
        schedule.setLastRunAt(now);
        schedule.setNextRunAt(computeNextRun(schedule, now));
        testScheduleRepository.save(schedule);

        log.info("Dispatched scheduled TestRun {} for schedule '{}' (Owner: {})",
                run.getId(), schedule.getName(), schedule.getOwnerId());

        runManager.executeRunAsync(run.getId(), schedule.getAuthType(), schedule.getAuthToken());
        return run;
    }

    private OffsetDateTime computeNextRun(TestSchedule schedule, OffsetDateTime from) {
        if (schedule.getScheduleType() == null) {
            return from.plusDays(1);
        }
        switch (schedule.getScheduleType()) {
            case DAILY:
                return from.plusDays(1);
            case WEEKLY:
                return from.plusWeeks(1);
            case CUSTOM_CRON:
            default:
                return from.plusMinutes(30);
        }
    }
}
