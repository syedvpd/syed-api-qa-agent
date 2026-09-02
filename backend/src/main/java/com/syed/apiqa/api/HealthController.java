package com.syed.apiqa.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/api/health", "/health", "/actuator/health", "/"})
    public ResponseEntity<Map<String, Object>> checkHealth() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Syed API QA Agent",
                "version", "1.0.0",
                "phase", "PRODUCTION_HARDENED",
                "llmDependency", "ZERO_LLM_DETERMINISTIC_ENGINE",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
