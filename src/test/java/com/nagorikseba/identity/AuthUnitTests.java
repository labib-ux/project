package com.nagorikseba.identity;

import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.service.ClientInfo;
import com.nagorikseba.identity.service.IdentifierNormalizer;
import com.nagorikseba.shared.config.JwtProperties;
import com.nagorikseba.shared.security.AuthenticatedUser;
import com.nagorikseba.shared.security.JwtTokenProvider;
import com.nagorikseba.shared.security.PrincipalContext;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The parts of Phase 2 that can be proven without a database: identifier
 * canonicalization, the lockout state machine, and access-token issue/verify.
 *
 * <p>These are deliberately plain JUnit tests — no Spring context, no Docker — so the
 * security-critical logic stays verifiable in environments where Testcontainers cannot
 * start. End-to-end behaviour lives in {@code AuthSecurityIntegrationTests}.
 */
class AuthUnitTests {

    private static final String SECRET =
            "bWFrZS1zdXJlLXRvLXJlcGxhY2UtdGhpcy1kZXZlbG9wbWVudC1qd3Qtc2VjcmV0LWJlZm9yZS1kZXBsb3ltZW50LTIwMjY=";
    private static final String ISSUER = "nagorik-seba";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    // ------------------------------------------------------- identifier normalization

    @ParameterizedTest
    @ValueSource(strings = {
            "01712345678",
            "+8801712345678",
            "8801712345678",
            "+880 17-1234 5678",
            "(01) 712345678",
            "  01712345678  "
    })
    void everyAcceptedPhoneSpellingCollapsesToTheCanonicalForm(String input) {
        assertThat(IdentifierNormalizer.normalizePhone(input)).isEqualTo("01712345678");
    }

    @Test
    void blankIdentifiersNormalizeToNullRatherThanEmptyString() {
        assertThat(IdentifierNormalizer.normalizePhone("   ")).isNull();
        assertThat(IdentifierNormalizer.normalizePhone(null)).isNull();
        assertThat(IdentifierNormalizer.normalizeEmail("")).isNull();
        assertThat(IdentifierNormalizer.normalizeEmail(null)).isNull();
        assertThat(IdentifierNormalizer.normalizeIdentifier(" ")).isNull();
    }

    @Test
    void emailsAreTrimmedAndLowerCased() {
        assertThat(IdentifierNormalizer.normalizeEmail("  Amina.Rahman@Example.COM "))
                .isEqualTo("amina.rahman@example.com");
    }

    @Test
    void identifierDispatchesOnTheAtSign() {
        assertThat(IdentifierNormalizer.normalizeIdentifier("Foo@Bar.com")).isEqualTo("foo@bar.com");
        assertThat(IdentifierNormalizer.normalizeIdentifier("+8801712345678")).isEqualTo("01712345678");
    }

    // ----------------------------------------------------------------- lockout policy

    @Test
    void theFifthFailureLocksTheAccountForTheConfiguredWindow() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        User user = User.builder().failedLoginCount(0).build();

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(user.registerFailedLogin(5, Duration.ofMinutes(15), now))
                    .as("attempt %d must not lock", attempt).isFalse();
            assertThat(user.isLocked(now)).isFalse();
        }

        assertThat(user.registerFailedLogin(5, Duration.ofMinutes(15), now)).isTrue();
        assertThat(user.getFailedLoginCount()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(user.isLocked(now)).isTrue();
    }

    @Test
    void aLockExpiresExactlyAtItsDeadlineNotAfterIt() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        User user = User.builder().failedLoginCount(4).build();
        user.registerFailedLogin(5, Duration.ofMinutes(15), now);

        assertThat(user.isLocked(now.plusSeconds(899))).isTrue();
        assertThat(user.isLocked(now.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void waitingOutALockGrantsAFreshWindowInsteadOfInstantRelock() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        User user = User.builder().failedLoginCount(4).build();
        user.registerFailedLogin(5, Duration.ofMinutes(15), now);

        Instant later = now.plus(Duration.ofMinutes(20));
        assertThat(user.registerFailedLogin(5, Duration.ofMinutes(15), later))
                .as("the first typo after a lapsed lock must not re-lock").isFalse();
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        assertThat(user.isLocked(later)).isFalse();
    }

    @Test
    void aSuccessfulLoginClearsTheCounterAndTheLock() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        User user = User.builder().failedLoginCount(4).build();
        user.registerFailedLogin(5, Duration.ofMinutes(15), now);

        user.registerSuccessfulLogin(now);

        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
        assertThat(user.isLocked(now)).isFalse();
    }

    @Test
    void canonicalIdentifierPrefersEmailAndFallsBackToPhone() {
        assertThat(User.builder().email("a@b.com").phone("01712345678").build().canonicalIdentifier())
                .isEqualTo("a@b.com");
        assertThat(User.builder().phone("01712345678").build().canonicalIdentifier())
                .isEqualTo("01712345678");
    }

    // ------------------------------------------------------------------ access tokens

    @Test
    void anIssuedTokenParsesBackIntoTheSamePrincipal() {
        JwtTokenProvider provider = provider(Instant.parse("2026-03-01T10:00:00Z"), 900);

        String token = provider.createAccessToken(42L, "amina@example.com", UserRole.DEPT_OFFICER, Set.of(7L, 9L));
        Optional<AuthenticatedUser> parsed = provider.parseAccessToken(token);

        assertThat(parsed).isPresent();
        AuthenticatedUser principal = parsed.orElseThrow();
        assertThat(principal.id()).isEqualTo(42L);
        assertThat(principal.identifier()).isEqualTo("amina@example.com");
        assertThat(principal.role()).isEqualTo(UserRole.DEPT_OFFICER);
        assertThat(principal.municipalityIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(principal.servesMunicipality(7L)).isTrue();
        assertThat(principal.servesMunicipality(8L)).isFalse();
        assertThat(principal.getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_DEPT_OFFICER");
    }

    @Test
    void thePrincipalItselfReportsRawMembershipWithNoAdminBypass() {
        JwtTokenProvider provider = provider(NOW, 900);

        AuthenticatedUser admin = provider
                .parseAccessToken(provider.createAccessToken(1L, "admin@example.com", UserRole.ADMIN, Set.of()))
                .orElseThrow();

        assertThat(admin.isAdmin()).isTrue();
        // The cross-tenant bypass deliberately lives in PrincipalContext, not here:
        // this accessor answers "is it in my membership set?" and nothing more.
        assertThat(admin.servesMunicipality(1234L)).isFalse();
        assertThat(admin.municipalityIds()).isEmpty();
    }

    @Test
    void principalContextGrantsAdminsEveryMunicipalityAndBlocksOtherRoles() {
        JwtTokenProvider provider = provider(NOW, 900);
        PrincipalContext context = new PrincipalContext();

        try {
            authenticateWith(provider, 1L, "admin@example.com", UserRole.ADMIN, Set.of());
            assertThat(context.isAdmin()).isTrue();
            assertThat(context.servesMunicipality(1234L)).as("admins are cross-tenant").isTrue();
            assertThatNoException().isThrownBy(() -> context.requireMunicipality(1234L));

            authenticateWith(provider, 7L, "officer@example.com", UserRole.DEPT_OFFICER, Set.of(3L));
            assertThat(context.requireUserId()).isEqualTo(7L);
            assertThat(context.municipalityIds()).containsExactly(3L);
            assertThat(context.servesMunicipality(3L)).isTrue();
            assertThat(context.servesMunicipality(4L)).isFalse();
            assertThatNoException().isThrownBy(() -> context.requireMunicipality(3L));
            assertThatExceptionOfType(AccessDeniedException.class)
                    .isThrownBy(() -> context.requireMunicipality(4L));

            assertThat(context.isOwnerOrAdmin(7L)).isTrue();
            assertThat(context.isOwnerOrAdmin(8L)).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void principalContextTreatsAnAnonymousCallAsUnauthenticated() {
        SecurityContextHolder.clearContext();
        PrincipalContext context = new PrincipalContext();

        assertThat(context.currentUser()).isEmpty();
        assertThat(context.isAuthenticated()).isFalse();
        assertThat(context.isAdmin()).isFalse();
        assertThat(context.municipalityIds()).isEmpty();
        assertThat(context.servesMunicipality(1L)).isFalse();
        assertThat(context.isOwnerOrAdmin(1L)).isFalse();
        assertThatExceptionOfType(AuthenticationCredentialsNotFoundException.class)
                .isThrownBy(context::requireUser);
        assertThatExceptionOfType(AuthenticationCredentialsNotFoundException.class)
                .isThrownBy(() -> context.requireMunicipality(1L));
    }

    @Test
    void aTamperedTokenIsRejected() {
        JwtTokenProvider provider = provider(Instant.parse("2026-03-01T10:00:00Z"), 900);
        String token = provider.createAccessToken(1L, "a@b.com", UserRole.CITIZEN, Set.of());

        assertThat(provider.parseAccessToken(token + "x")).isEmpty();
        assertThat(provider.parseAccessToken(token.substring(0, token.length() - 4))).isEmpty();
        assertThat(provider.parseAccessToken("not-a-jwt")).isEmpty();
    }

    @Test
    void anExpiredTokenIsRejected() {
        Instant issuedAt = Instant.parse("2026-03-01T10:00:00Z");
        String token = provider(issuedAt, 900)
                .createAccessToken(1L, "a@b.com", UserRole.CITIZEN, Set.of());

        // Same key, same issuer, correct type — only the clock has moved past expiry.
        assertThat(provider(issuedAt.plusSeconds(899), 900).parseAccessToken(token)).isPresent();
        assertThat(provider(issuedAt.plusSeconds(901), 900).parseAccessToken(token)).isEmpty();
        assertThat(provider(issuedAt.plus(Duration.ofDays(1)), 900).parseAccessToken(token)).isEmpty();
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        JwtTokenProvider provider = provider(NOW, 900);
        String foreign = Jwts.builder()
                .subject("a@b.com")
                .issuer("some-other-app")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(900)))
                .claim(JwtTokenProvider.CLAIM_USER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_ROLE, "ADMIN")
                .claim(JwtTokenProvider.CLAIM_MUNICIPALITY_IDS, List.of())
                .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, JwtTokenProvider.TOKEN_TYPE_ACCESS)
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(provider.parseAccessToken(foreign)).isEmpty();
    }

    @Test
    void aTokenOfAnotherTypeCannotBeReplayedAsAnAccessToken() {
        JwtTokenProvider provider = provider(NOW, 900);
        String wrongType = Jwts.builder()
                .subject("a@b.com")
                .issuer(ISSUER)
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(900)))
                .claim(JwtTokenProvider.CLAIM_USER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_ROLE, "ADMIN")
                .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, "REFRESH")
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(provider.parseAccessToken(wrongType)).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        JwtTokenProvider provider = provider(NOW, 900);
        String otherSecret = "YW5vdGhlci1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaG1hYy1zaGEtMjU2LXNpZ25pbmc=";
        String forged = Jwts.builder()
                .subject("a@b.com")
                .issuer(ISSUER)
                .expiration(Date.from(NOW.plusSeconds(900)))
                .claim(JwtTokenProvider.CLAIM_USER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_ROLE, "ADMIN")
                .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, JwtTokenProvider.TOKEN_TYPE_ACCESS)
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(otherSecret)))
                .compact();

        assertThat(provider.parseAccessToken(forged)).isEmpty();
    }

    // -------------------------------------------------------------------- client info

    @Test
    void clientInfoParsesLiteralAddressesOnly() throws Exception {
        assertThat(ClientInfo.of("203.0.113.7", "curl/8.4").ipAddress())
                .isEqualTo(java.net.InetAddress.getByName("203.0.113.7"));
        assertThat(ClientInfo.of("::1", null).ipAddress())
                .isEqualTo(java.net.InetAddress.getByName("::1"));

        // A hostname must never trigger a DNS lookup from a request-supplied value.
        assertThat(ClientInfo.of("attacker.example.com", null).ipAddress()).isNull();
        assertThat(ClientInfo.of(null, null).ipAddress()).isNull();
        assertThat(ClientInfo.of("", null).ipAddress()).isNull();
    }

    @Test
    void clientInfoTruncatesTheUserAgentToTheColumnWidth() {
        String long1000 = "x".repeat(1000);

        assertThat(ClientInfo.of("203.0.113.7", long1000).userAgent()).hasSize(255);
        assertThat(ClientInfo.of("203.0.113.7", "  Mozilla/5.0  ").userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(ClientInfo.of("203.0.113.7", "   ").userAgent()).isNull();
    }

    // ------------------------------------------------------------------------ helpers

    /**
     * Populates the SecurityContext the way {@code JwtAuthenticationFilter} does — by
     * round-tripping a real token — so the context tests exercise the true claim path.
     */
    private void authenticateWith(JwtTokenProvider provider, Long userId, String subject,
                                  UserRole role, Set<Long> municipalityIds) {
        AuthenticatedUser principal = provider
                .parseAccessToken(provider.createAccessToken(userId, subject, role, municipalityIds))
                .orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private JwtTokenProvider provider(Instant issuedAt, long accessTokenSeconds) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer(ISSUER);
        properties.setAccessTokenSeconds(accessTokenSeconds);
        return new JwtTokenProvider(properties, Clock.fixed(issuedAt, ZoneOffset.UTC));
    }
}
