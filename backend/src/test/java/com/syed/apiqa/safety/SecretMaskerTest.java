package com.syed.apiqa.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretMaskerTest {

    private SecretMasker masker;

    @BeforeEach
    void setUp() {
        masker = new SecretMasker();
    }

    @Test
    void shouldMaskAuthorizationHeader() {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("Authorization", Collections.singletonList("Bearer eyJhbGciOi..."));
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("X-Api-Key", Collections.singletonList("secret-api-key-123"));

        Map<String, List<String>> masked = masker.maskHeaders(headers);

        assertEquals(SecretMasker.REDACTED_MARKER, masked.get("Authorization").get(0));
        assertEquals("application/json", masked.get("Content-Type").get(0));
        assertEquals(SecretMasker.REDACTED_MARKER, masked.get("X-Api-Key").get(0));
    }

    @Test
    void shouldMaskSensitiveJsonBodyFields() {
        String rawJson = "{\"username\":\"admin\",\"password\":\"superSecretPassword123\",\"token\":\"jwt.xyz\"}";
        String maskedJson = masker.maskBody(rawJson);

        assertTrue(maskedJson.contains("\"password\":\"[REDACTED]\""));
        assertTrue(maskedJson.contains("\"token\":\"[REDACTED]\""));
        assertTrue(maskedJson.contains("\"username\":\"admin\""));
        assertFalse(maskedJson.contains("superSecretPassword123"));
    }
}
