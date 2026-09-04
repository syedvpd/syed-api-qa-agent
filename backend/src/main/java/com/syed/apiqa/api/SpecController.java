package com.syed.apiqa.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Public endpoint for serving immutable, frozen OpenAPI contracts.
 * Enables reproducible baseline execution against verified live public targets.
 */
@RestController
@RequestMapping("/api/specs")
public class SpecController {

    @GetMapping(value = "/{specName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSpecification(@PathVariable String specName) {
        String cleanName = specName.replaceAll("[^a-zA-Z0-9_.-]", "");
        if (!cleanName.endsWith(".json")) {
            cleanName += ".json";
        }
        try {
            Resource resource = new ClassPathResource("static/" + cleanName);
            if (!resource.exists()) {
                resource = new ClassPathResource("static/" + cleanName.replace(".json", "_openapi.json"));
            }
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return ResponseEntity.ok(content);
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("{\"error\":\"Failed to load spec: " + e.getMessage() + "\"}");
        }
    }
}
