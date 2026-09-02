package com.nagorikseba.identity.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Failed-login throttling policy (§8.1 defaults: 5 attempts, 15 minutes).
 *
 * <p>Bound from {@code app.security.lockout.*} so an operator can tighten it
 * without a redeploy.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.security.lockout")
public class LockoutProperties {

    @Min(1)
    private int maxFailedAttempts = 5;

    @NotNull
    private Duration duration = Duration.ofMinutes(15);
}
