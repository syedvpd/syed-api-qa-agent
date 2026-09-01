package com.syed.apiqa.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Controller for stateless authentication token issuance.
 * Enables client applications and automated CI/CD pipelines to obtain cryptographically signed tokens.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenSecurityService tokenSecurityService;

    public AuthController(TokenSecurityService tokenSecurityService) {
        this.tokenSecurityService = tokenSecurityService;
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> issueToken(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }

        // Default 24 hour TTL for generated tokens
        long ttlSeconds = 86400;
        String token = tokenSecurityService.issueToken(userId.trim(), Duration.ofSeconds(ttlSeconds));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "expiresIn", ttlSeconds,
                "userId", userId.trim()
        ));
    }
}
