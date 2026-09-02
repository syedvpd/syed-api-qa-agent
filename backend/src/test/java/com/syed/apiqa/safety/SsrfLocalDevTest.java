package com.syed.apiqa.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SsrfLocalDevTest {

    private SsrfProtectionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SsrfProtectionGuard();
        ReflectionTestUtils.setField(guard, "ssrfProtectionEnabled", true);
        ReflectionTestUtils.setField(guard, "allowLocalTargets", false);
    }

    @Test
    void shouldBlockLocalhostInProductionMode() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                guard.resolveAndValidate("http://localhost:8080/v3/api-docs", false));
        assertTrue(ex.getMessage().contains("strictly blocked in production mode"));

        assertThrows(SecurityException.class, () ->
                guard.resolveAndValidate("http://127.0.0.1:8080/v3/api-docs", false));
    }

    @Test
    void shouldAllowLocalhostInDevelopmentMode() {
        // When allowLocal is true, localhost and 127.0.0.1 resolve cleanly
        SsrfProtectionGuard.ValidatedTarget target = guard.resolveAndValidate("http://127.0.0.1:8080/v3/api-docs", true);
        assertNotNull(target);
        assertEquals("127.0.0.1", target.originalHost());
        assertEquals(8080, target.port());
    }

    @Test
    void shouldAlwaysBlockCloudMetadataEvenInDevelopmentMode() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                guard.resolveAndValidate("http://169.254.169.254/latest/meta-data/", true));
        assertTrue(ex.getMessage().contains("strictly blocked") || ex.getMessage().contains("Cloud metadata"));
    }
}
