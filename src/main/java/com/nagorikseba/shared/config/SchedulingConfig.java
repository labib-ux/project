package com.nagorikseba.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduler infrastructure switch (Phase 5).
 *
 * <p>Scheduling is off unless {@code app.scheduling.enabled=true}: the test
 * profile sets it false so background workers never disturb unrelated suites,
 * and scheduler-specific tests opt back in explicitly. Production enablement
 * lands with {@code application-prod.yml} in Phase 6.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class SchedulingConfig {
}
