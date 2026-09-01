package com.syed.apiqa.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TokenSecurityServiceTest {

    private TokenSecurityService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenSecurityService("my-secure-production-secret-key-at-least-32-chars-long!");
    }

    @Test
    void shouldIssueAndValidateValidToken() {
        String token = tokenService.issueToken("user-alice", Duration.ofHours(1));
        assertNotNull(token);
        assertTrue(token.startsWith("syed_sec_v1."));

        String validatedUser = tokenService.validateToken(token);
        assertEquals("user-alice", validatedUser);
    }

    @Test
    void shouldRejectExpiredToken() throws InterruptedException {
        // Issue token that expires in 1 millisecond
        String token = tokenService.issueToken("user-bob", Duration.ofMillis(1));
        Thread.sleep(1100);

        SecurityException ex = assertThrows(SecurityException.class, () -> tokenService.validateToken(token));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    void shouldRejectForgedOrTamperedSignature() {
        String token = tokenService.issueToken("user-alice", Duration.ofHours(1));
        int lastDot = token.lastIndexOf('.');
        String tamperedToken = token.substring(0, lastDot + 1) + "forgedSignatureBase64String12345=";

        SecurityException ex = assertThrows(SecurityException.class, () -> tokenService.validateToken(tamperedToken));
        assertTrue(ex.getMessage().contains("Cryptographic signature verification failed"));
    }

    @Test
    void shouldRejectModifiedPayloadWithOriginalSignature() {
        String token = tokenService.issueToken("user-alice", Duration.ofHours(1));
        int firstDot = token.indexOf('.');
        int lastDot = token.lastIndexOf('.');
        String signature = token.substring(lastDot);
        String payloadBase64 = token.substring(firstDot + 1, lastDot);
        // Modify the base64 payload
        String tamperedPayload = (payloadBase64.charAt(0) == 'A' ? 'B' : 'A') + payloadBase64.substring(1);
        String tamperedToken = token.substring(0, firstDot + 1) + tamperedPayload + signature;

        SecurityException ex = assertThrows(SecurityException.class, () -> tokenService.validateToken(tamperedToken));
        assertTrue(ex.getMessage().contains("verification failed"));
    }

    @Test
    void shouldRejectMalformedToken() {
        assertThrows(SecurityException.class, () -> tokenService.validateToken("not-a-token"));
        assertThrows(SecurityException.class, () -> tokenService.validateToken("syed_sec_v1.missingDot"));
    }
}
