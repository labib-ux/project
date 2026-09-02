package com.nagorikseba.shared.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token lifetimes and signing material (§8.1).
 *
 * <p>Defaults are the blueprint values: 15-minute access tokens, 30-day refresh
 * tokens. The secret has a development default in {@code application.yml} and must
 * come from {@code JWT_SECRET} in the {@code prod} profile.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    @NotBlank
    private String secret;

    /** Access-token TTL. Short by design: revocation happens by expiry. */
    @Min(60)
    private long accessTokenSeconds = 900;

    /** Refresh-token TTL; rotated on every use. */
    @Min(1)
    private int refreshTokenDays = 30;

    @NotBlank
    private String issuer = "nagorik-seba";
}
