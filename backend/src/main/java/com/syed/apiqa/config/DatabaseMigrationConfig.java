package com.syed.apiqa.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production Flyway migration strategy.
 * Executes flyway.repair() prior to flyway.migrate() so that any transient failed
 * migrations in cloud environments are automatically reconciled without manual database intervention.
 */
@Configuration
public class DatabaseMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                log.info("Executing Flyway repair to clean any failed migration states...");
                flyway.repair();
            } catch (Exception e) {
                log.warn("Flyway repair completed or skipped: {}", e.getMessage());
            }
            log.info("Executing Flyway migrate...");
            flyway.migrate();
        };
    }
}
