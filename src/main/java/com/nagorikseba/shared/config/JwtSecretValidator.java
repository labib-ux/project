package com.nagorikseba.shared.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the JWT signing secret.
 *
 * <p>In the {@code prod} profile the secret has no default
 * ({@code application-prod.yml} references {@code ${JWT_SECRET}} with none),
 * so a missing variable already fails placeholder resolution. This validator
 * covers the remaining hole — a present-but-blank value — in every profile.
 */
@Component
@Slf4j
public class JwtSecretValidator {

    private final String secret;

    public JwtSecretValidator(@Value("${security.jwt.secret:}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    public void requireSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured: set the JWT_SECRET environment variable");
        }
        log.debug("JWT secret configured ({} chars)", secret.length());
    }
}
