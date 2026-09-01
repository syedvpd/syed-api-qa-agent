package com.syed.apiqa.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Lightweight, zero-dependency cryptographic token service.
 * Issues and verifies HMAC-SHA256 signed stateless authentication tokens.
 * Enforces tamper-proof user identities to prevent X-User-Id spoofing.
 */
@Service
public class TokenSecurityService {

    private static final Logger log = LoggerFactory.getLogger(TokenSecurityService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX = "syed_sec_v1.";

    private final byte[] secretKeyBytes;

    public TokenSecurityService(
            @Value("${syed.security.auth-secret:syed-apiqa-prod-secret-must-be-configured-in-env-32bytes}") String secretKey) {
        if (secretKey == null || secretKey.length() < 16) {
            log.warn("syed.security.auth-secret is short or default. Using SHA-256 derived key.");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            this.secretKeyBytes = sha256.digest(secretKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize TokenSecurityService key", e);
        }
    }

    /**
     * Issues a signed token for the given userId with a defined time-to-live.
     */
    public String issueToken(String userId, Duration ttl) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be empty");
        }
        long expiresAtEpochSec = Instant.now().plus(ttl).getEpochSecond();
        String payload = userId.trim() + ":" + expiresAtEpochSec;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String signature = sign(encodedPayload);
        return TOKEN_PREFIX + encodedPayload + "." + signature;
    }

    /**
     * Validates the signed token.
     * Returns the verified userId if valid and not expired.
     * Throws SecurityException if signature is invalid or token is expired/malformed.
     */
    public String validateToken(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new SecurityException("Invalid token format: missing prefix");
        }

        String raw = token.substring(TOKEN_PREFIX.length());
        int dotIndex = raw.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= raw.length() - 1) {
            throw new SecurityException("Malformed token structure");
        }

        String encodedPayload = raw.substring(0, dotIndex);
        String receivedSignature = raw.substring(dotIndex + 1);

        String expectedSignature = sign(encodedPayload);
        // Constant-time comparison against timing attacks
        if (!MessageDigest.isEqual(
                receivedSignature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Cryptographic signature verification failed: token has been forged or tampered with");
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecurityException("Failed to decode token payload", e);
        }

        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            throw new SecurityException("Invalid token payload structure");
        }

        String userId = payload.substring(0, colon);
        long expiresAt;
        try {
            expiresAt = Long.parseLong(payload.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new SecurityException("Invalid token expiration format");
        }

        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new SecurityException("Token expired at " + expiresAt);
        }

        return userId;
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_ALGO));
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new SecurityException("Failed to calculate HMAC signature", e);
        }
    }
}
