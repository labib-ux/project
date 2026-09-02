package com.nagorikseba.shared.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Blueprint S17 — single source of "now" for the whole application.
 *
 * <p>Every clock read goes through this bean so tests can substitute a fixed or
 * offset clock (see {@code AuthSecurityIntegrationTests} lockout/expiry cases)
 * instead of sleeping. The clock is UTC because all persistence is UTC
 * ({@code timestamptz}); {@code Asia/Dhaka} is a presentation concern only.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
