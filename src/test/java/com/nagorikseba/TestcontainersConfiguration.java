package com.nagorikseba;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test infrastructure (Blueprint D9): provides a PostgreSQL 16 + PostGIS 3.4
 * container for integration tests. {@code @ServiceConnection} wires the
 * datasource (URL/username/password) automatically, so Flyway runs the real
 * migrations (including PostGIS DDL) against a production-like database.
 *
 * Tests opt in via {@code @Import(TestcontainersConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    }
}