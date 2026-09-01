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
    void shouldRejectGoogleCloudMetadata() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                ssrfGuard.validateTargetUrl("http://metadata.google.internal/computeMetadata/v1/"));
        assertTrue(exception.getMessage().contains("SSRF Guard"));
    }

    @Test
    void shouldRejectPrivateIpv4Ranges() {
        assertThrows(SecurityException.class, () -> ssrfGuard.validateTargetUrl("http://10.0.0.1/api"));
        assertThrows(SecurityException.class, () -> ssrfGuard.validateTargetUrl("http://192.168.1.1/api"));
        assertThrows(SecurityException.class, () -> ssrfGuard.validateTargetUrl("http://172.16.0.1/api"));
    }

    @Test
    void shouldRejectWildcardAnyLocalAddress() {
        assertThrows(SecurityException.class, () -> ssrfGuard.validateTargetUrl("http://0.0.0.0/api"));
    }

    @Test
    void shouldRejectCarrierGradeNat() {
        assertThrows(SecurityException.class, () -> ssrfGuard.validateTargetUrl("http://100.64.0.1/api"));
    }

    @Test
    void shouldRejectUserInfoUrls() {
        SecurityException exception = assertThrows(SecurityException.class, () ->
                ssrfGuard.validateTargetUrl("http://admin:secret@attacker.com/spec.json"));
        assertTrue(exception.getMessage().contains("userinfo"));
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

    @Test
    void shouldReturnPinnedTargetForAntiDnsRebinding() {
        SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate("https://petstore.swagger.io/v2/swagger.json");
        assertNotNull(target);
        assertNotNull(target.pinnedAddress());
        assertTrue(target.isPinned());
        assertNotNull(target.pinnedUrl());
        assertEquals("petstore.swagger.io", target.originalHost());
    }
}
