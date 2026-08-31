package com.syed.apiqa.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> checkHealth() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Syed API QA Agent",
                "version", "0.1.0-SNAPSHOT",
                "phase", "PHASE_0_FOUNDATION",
                "llmDependency", "ZERO_LLM_DETERMINISTIC_ENGINE",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
