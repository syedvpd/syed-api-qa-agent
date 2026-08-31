package com.syed.apiqa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SyedApiQaApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the Spring Boot 3 context, JPA entity mappings,
        // repositories, and configurations initialize cleanly.
    }
}
