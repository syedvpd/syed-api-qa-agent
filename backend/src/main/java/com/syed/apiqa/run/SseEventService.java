package com.syed.apiqa.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time Server-Sent Events (SSE) event broadcaster with reconnect resilience.
 * Supports multiple concurrent subscribers per TestRun.
 * Client disconnects are safely handled without affecting backend execution.
 * Maintains an in-memory ring-buffer backlog to replay events upon reconnect.
 */
@Service
public class SseEventService {

    private static final Logger log = LoggerFactory.getLogger(SseEventService.class);
    private static final int MAX_BACKLOG_SIZE = 50;

    private final Map<String, List<SseEmitter>> emittersByRunId = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> eventBacklogByRunId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String testRunId) {
        // 30 minute timeout for long-running tests
        SseEmitter emitter = new SseEmitter(1800_000L);

        emittersByRunId.computeIfAbsent(testRunId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(testRunId, emitter));
        emitter.onTimeout(() -> removeEmitter(testRunId, emitter));
        emitter.onError((e) -> removeEmitter(testRunId, emitter));

        // Send initial connection event
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("message", "Connected to test run stream for " + testRunId, "timestamp", System.currentTimeMillis())));

            // Replay historical backlog if client reconnected
            List<Map<String, Object>> backlog = eventBacklogByRunId.get(testRunId);
            if (backlog != null) {
                for (Map<String, Object> pastEvent : backlog) {
                    String type = (String) pastEvent.getOrDefault("eventType", "EVENT");
                    emitter.send(SseEmitter.event().name(type).data(pastEvent));
                }
            }
        } catch (IOException e) {
            removeEmitter(testRunId, emitter);
        }

        return emitter;
    }

    private static final Set<String> TERMINAL_EVENTS = Set.of(
            "RUN_COMPLETED", "RUN_FAILED", "RUN_TIMED_OUT", "RUN_CANCELLED");

    public void publishEvent(String testRunId, String eventType, Map<String, Object> payload) {
        Map<String, Object> eventData = new HashMap<>(payload);
        eventData.put("eventType", eventType);
        eventData.put("timestamp", System.currentTimeMillis());

        // Append to circular backlog
        List<Map<String, Object>> backlog = eventBacklogByRunId.computeIfAbsent(testRunId, k -> new CopyOnWriteArrayList<>());
        backlog.add(eventData);
        if (backlog.size() > MAX_BACKLOG_SIZE) {
            backlog.remove(0);
        }

        List<SseEmitter> emitters = emittersByRunId.get(testRunId);
        if (emitters == null || emitters.isEmpty()) {
            // If terminal and no subscribers, schedule deferred cleanup (60s) so late subscribers can still replay
            if (TERMINAL_EVENTS.contains(eventType)) {
                new Thread(() -> {
                    try { Thread.sleep(60_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    eventBacklogByRunId.remove(testRunId);
                    emittersByRunId.remove(testRunId);
                }, "sse-cleanup-" + testRunId).start();
            }
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .data(eventData));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);

        // Evict backlog on terminal events to prevent memory leak
        if (TERMINAL_EVENTS.contains(eventType)) {
            // Schedule deferred cleanup to allow final reconnects
            new Thread(() -> {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                eventBacklogByRunId.remove(testRunId);
                emittersByRunId.remove(testRunId);
            }, "sse-cleanup-" + testRunId).start();
        }
    }

    private void removeEmitter(String testRunId, SseEmitter emitter) {
        List<SseEmitter> list = emittersByRunId.get(testRunId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByRunId.remove(testRunId);
            }
        }
    }
}
