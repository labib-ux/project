package com.nagorikseba.shared.security;

import com.nagorikseba.enums.UserRole;
import com.nagorikseba.shared.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Blueprint S7 — access-token issue/parse (§8.1).
 *
 * <p>Claim contract: {@code sub} (canonical identifier), {@code uid} (user id),
 * {@code role}, {@code mids} (current municipality ids) and {@code typ}, which is
 * always {@code ACCESS}. {@code typ} exists so that a token of another kind can
 * never be replayed as an access token; refresh tokens are opaque and are not
 * JWTs at all.
 *
 * <p>The token is self-contained on purpose: authorization for an API call costs
 * zero queries. The price is a bounded staleness window equal to the token TTL
 * (15 min) for role/membership changes and deactivations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenProvider {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_MUNICIPALITY_IDS = "mids";
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";

    private final JwtProperties properties;
    private final Clock clock;

    /**
     * Mints an access token. Takes primitives rather than the identity {@code User}
     * entity so that {@code shared} keeps depending on no feature module.
     */
    public String createAccessToken(Long userId, String subject, UserRole role, Collection<Long> municipalityIds) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(subject)
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getAccessTokenSeconds())))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_MUNICIPALITY_IDS, municipalityIds == null ? List.of() : List.copyOf(municipalityIds))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .signWith(signingKey())
                .compact();
    }

    /**
     * Verifies signature, issuer, expiry and token type, then rebuilds the
     * principal. Returns {@link Optional#empty()} for every kind of invalid token —
     * callers must not distinguish "expired" from "forged" to the client.
     */
    public Optional<AuthenticatedUser> parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(properties.getIssuer())
                    .require(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                    // Expiry is checked against the injected Clock, not wall-clock time, so
                    // issue and verify share one time source (S17).
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = readLong(claims.get(CLAIM_USER_ID));
            String subject = claims.getSubject();
            UserRole role = UserRole.valueOf(String.valueOf(claims.get(CLAIM_ROLE)));
            if (userId == null || subject == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(userId, subject, role, readMunicipalityIds(claims)));
        } catch (JwtException | IllegalArgumentException exception) {
            // Never log the token itself.
            log.debug("Rejected access token: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public long getAccessTokenSeconds() {
        return properties.getAccessTokenSeconds();
    }

    private Set<Long> readMunicipalityIds(Claims claims) {
        Object raw = claims.get(CLAIM_MUNICIPALITY_IDS);
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : values) {
            Long id = readLong(value);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** JSON numbers deserialize as Integer or Long depending on magnitude. */
    private Long readLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.getSecret()));
    }
}
