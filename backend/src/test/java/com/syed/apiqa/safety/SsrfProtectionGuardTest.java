package com.syed.apiqa.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SsrfProtectionGuardTest {

    private SsrfProtectionGuard ssrfGuard;

    @BeforeEach
    void setUp() {
        ssrfGuard = new SsrfProtectionGuard();
        ReflectionTestUtils.setField(ssrfGuard, "ssrfProtectionEnabled", true);
    }

    @Test
    void shouldRejectLocalhost() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                ssrfGuard.validateTargetUrl("http://localhost:8080/api-docs"));
        assertTrue(exception.getMessage().contains("SSRF Guard"));
    }

    @Test
    void shouldRejectLoopbackIp() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                ssrfGuard.validateTargetUrl("http://127.0.0.1:3000/swagger.json"));
        assertTrue(exception.getMessage().contains("SSRF Guard"));
    }

    @Test
    void shouldRejectCloudMetadataIp() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                ssrfGuard.validateTargetUrl("http://169.254.169.254/latest/meta-data/"));
        assertTrue(exception.getMessage().contains("SSRF Guard"));
    }

    @Test
    void shouldRejectNonHttpProtocols() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                ssrfGuard.validateTargetUrl("file:///etc/passwd"));
        assertTrue(exception.getMessage().contains("Blocked insecure protocol"));
    }

    @Test
    void shouldAcceptValidPublicUrl() {
        assertDoesNotThrow(() ->
                ssrfGuard.validateTargetUrl("https://petstore.swagger.io/v2/swagger.json"));
    }
}
