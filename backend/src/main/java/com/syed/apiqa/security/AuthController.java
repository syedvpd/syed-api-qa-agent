package com.syed.apiqa.security;

import com.syed.apiqa.domain.UserCredential;
import com.syed.apiqa.persistence.UserCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller for secure multi-tenant authentication token issuance.
 * Enforces cryptographic identity ownership:
 * - Anonymous browser clients provision an isolated, persistent identity and secret.
 * - Claiming an existing identity requires matching user credentials.
 * - Machine-to-machine CI/CD pipelines authenticate with configured API keys.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final TokenSecurityService tokenSecurityService;
    private final UserCredentialRepository userCredentialRepository;

    @Value("${syed.security.ci-api-key:ci-pipeline-default-secret-key-32b}")
    private String ciApiKey;

    public AuthController(TokenSecurityService tokenSecurityService,
                          UserCredentialRepository userCredentialRepository) {
        this.tokenSecurityService = tokenSecurityService;
        this.userCredentialRepository = userCredentialRepository;
    }

    @PostMapping("/token")
    public ResponseEntity<?> issueToken(@RequestBody(required = false) Map<String, String> request) {
        String apiKey = (request != null) ? request.get("apiKey") : null;
        String requestedUserId = (request != null) ? request.get("userId") : null;
        String userSecret = (request != null) ? (request.get("userSecret") != null ? request.get("userSecret") : request.get("password")) : null;

        long ttlSeconds = 86400; // 24 hours
        Instant expiresAtInstant = Instant.now().plusSeconds(ttlSeconds);
        String expiresAtIso = expiresAtInstant.toString();

        // 1. Machine-to-Machine CI/CD Authentication
        if (apiKey != null && !apiKey.isBlank()) {
            if (apiKey.trim().equals(ciApiKey.trim())) {
                String token = tokenSecurityService.issueToken("ci-pipeline", Duration.ofSeconds(ttlSeconds));
                return ResponseEntity.ok(Map.of(
                        "token", token,
                        "tokenType", "Bearer",
                        "expiresIn", ttlSeconds,
                        "expiresAt", expiresAtIso,
                        "userId", "ci-pipeline"
                ));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "error", "INVALID_API_KEY",
                        "message", "The provided machine-to-machine apiKey is invalid."
                ));
            }
        }

        // 2. New Anonymous Browser Identity Registration / Session Provisioning
        if (requestedUserId == null || requestedUserId.isBlank()) {
            String newUserId = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String newSecret = UUID.randomUUID().toString().replace("-", "");
            String secretHash = hashSecret(newSecret);

            UserCredential cred = new UserCredential(newUserId, secretHash, "WEB_USER", OffsetDateTime.now());
            userCredentialRepository.save(cred);

            String token = tokenSecurityService.issueToken(newUserId, Duration.ofSeconds(ttlSeconds));
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", ttlSeconds,
                    "expiresAt", expiresAtIso,
                    "userId", newUserId,
                    "userSecret", newSecret
            ));
        }

        // 3. Existing Identity Authentication with Verification
        String trimmedUserId = requestedUserId.trim();
        Optional<UserCredential> existing = userCredentialRepository.findById(trimmedUserId);

        if (existing.isPresent()) {
            if (userSecret == null || userSecret.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "error", "CREDENTIALS_REQUIRED",
                        "message", "User identity " + trimmedUserId + " is protected. userSecret is required."
                ));
            }
            String providedHash = hashSecret(userSecret.trim());
            if (!MessageDigest.isEqual(providedHash.getBytes(StandardCharsets.UTF_8),
                    existing.get().getSecretHash().getBytes(StandardCharsets.UTF_8))) {
                log.warn("Unauthorized impersonation attempt against userId: {}", trimmedUserId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "error", "INVALID_CREDENTIALS",
                        "message", "Invalid credentials for userId: " + trimmedUserId
                ));
            }
            // Verified
            String token = tokenSecurityService.issueToken(trimmedUserId, Duration.ofSeconds(ttlSeconds));
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", ttlSeconds,
                    "expiresAt", expiresAtIso,
                    "userId", trimmedUserId
            ));
        } else {
            // New named user registration with provided secret
            if (userSecret == null || userSecret.isBlank()) {
                userSecret = UUID.randomUUID().toString().replace("-", "");
            }
            String secretHash = hashSecret(userSecret.trim());
            UserCredential cred = new UserCredential(trimmedUserId, secretHash, "WEB_USER", OffsetDateTime.now());
            userCredentialRepository.save(cred);

            String token = tokenSecurityService.issueToken(trimmedUserId, Duration.ofSeconds(ttlSeconds));
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", ttlSeconds,
                    "expiresAt", expiresAtIso,
                    "userId", trimmedUserId,
                    "userSecret", userSecret
            ));
        }
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash secret", e);
        }
    }
}
