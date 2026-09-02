package com.nagorikseba.identity.service;

import com.nagorikseba.identity.domain.RefreshToken;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.RefreshTokenRepository;
import com.nagorikseba.shared.config.JwtProperties;
import com.nagorikseba.shared.exception.InvalidRefreshTokenException;
import com.nagorikseba.shared.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Blueprint I8 — issues and rotates the access/refresh pair (§8.1, R9).
 *
 * <p>Access tokens are short-lived JWTs; refresh tokens are opaque 256-bit random
 * strings stored only as a SHA-256 digest. Each refresh call revokes the presented
 * token and issues a successor, linked through {@code replaced_by_token_id}.
 *
 * <p><strong>Reuse detection.</strong> Presenting a token that was already rotated
 * away means either a replay or a stolen token. Since the legitimate client and the
 * attacker are indistinguishable at that point, the entire family is revoked and both
 * are forced to log in again.
 *
 * <p>Raw tokens are never logged — only user ids and token ids.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    /** 256 bits of entropy, per §8.1. */
    private static final int TOKEN_BYTES = 32;

    /** Guards the family walk against a malformed cycle in the chain. */
    private static final int MAX_FAMILY_SIZE = 1_000;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final RefreshTokenRepository refreshTokenRepository;
    private final AppUserDetailsService appUserDetailsService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder tokenEncoder = Base64.getUrlEncoder().withoutPadding();

    /**
     * A freshly minted credential pair.
     *
     * @param refreshTokenId id of the persisted refresh token, so a rotation can link
     *                       its predecessor to it
     */
    public record TokenPair(
            User user,
            String accessToken,
            String refreshToken,
            Set<Long> municipalityIds,
            Long refreshTokenId
    ) {
    }

    /** Issues a new access token plus a new refresh-token family root. */
    @Transactional
    public TokenPair issue(User user, ClientInfo clientInfo) {
        Instant now = clock.instant();
        Set<Long> municipalityIds = appUserDetailsService.currentMunicipalityIds(user.getId());
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(), user.canonicalIdentifier(), user.getRole(), municipalityIds);

        String rawRefreshToken = generateRawToken();
        ClientInfo client = clientInfo == null ? ClientInfo.UNKNOWN : clientInfo;
        RefreshToken stored = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawRefreshToken))
                .expiresAt(now.plus(Duration.ofDays(jwtProperties.getRefreshTokenDays())))
                .ipAddress(client.ipAddress())
                .userAgent(client.userAgent())
                .build());

        return new TokenPair(user, accessToken, rawRefreshToken, municipalityIds, stored.getId());
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>{@code noRollbackFor} matters: when reuse is detected we revoke the family and
     * <em>then</em> throw. Without it the throw would roll the revocation back and the
     * leaked family would stay alive.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired or already rotated
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPair rotate(String rawRefreshToken, ClientInfo clientInfo) {
        Instant now = clock.instant();
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        Long userId = presented.getUser().getId();

        if (presented.isRevoked()) {
            int revoked = revokeFamily(presented, now);
            log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in the family",
                    userId, revoked);
            throw new InvalidRefreshTokenException();
        }
        if (presented.isExpired(now)) {
            presented.revoke(now);
            refreshTokenRepository.save(presented);
            log.debug("Expired refresh token presented for user {}", userId);
            throw new InvalidRefreshTokenException();
        }

        User user = presented.getUser();
        if (!user.isActive()) {
            revokeFamily(presented, now);
            log.warn("Refresh attempted for deactivated user {}; family revoked", userId);
            throw new InvalidRefreshTokenException();
        }

        TokenPair next = issue(user, clientInfo);
        presented.revoke(now);
        presented.setReplacedByTokenId(next.refreshTokenId());
        refreshTokenRepository.save(presented);
        return next;
    }

    /**
     * Logout: revokes the presented token and its family.
     *
     * <p>Idempotent, and silent about whether the token existed — logout must not become
     * an oracle for guessing valid tokens.
     */
    @Transactional
    public void revoke(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .ifPresent(token -> revokeFamily(token, now));
    }

    /** Revokes every live token of every family belonging to a user. */
    @Transactional
    public int revokeAllForUser(Long userId) {
        Instant now = clock.instant();
        List<RefreshToken> active = refreshTokenRepository.findActiveTokenFamilies(userId, now);
        if (active.isEmpty()) {
            return 0;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (RefreshToken token : active) {
            ids.addAll(collectFamilyIds(token));
        }
        return refreshTokenRepository.revokeFamily(ids, now);
    }

    /**
     * Revokes the whole rotation chain the given token belongs to.
     *
     * <p>Walks backwards to the root via {@code findByReplacedByTokenId}, then forwards
     * through {@code replacedByTokenId}, so it does not matter which link is presented.
     */
    private int revokeFamily(RefreshToken member, Instant now) {
        Set<Long> ids = collectFamilyIds(member);
        return refreshTokenRepository.revokeFamily(ids, now);
    }

    private Set<Long> collectFamilyIds(RefreshToken member) {
        RefreshToken root = walkToRoot(member);
        Set<Long> ids = new LinkedHashSet<>();
        RefreshToken current = root;
        while (current != null && ids.size() < MAX_FAMILY_SIZE) {
            if (!ids.add(current.getId())) {
                break;
            }
            Long successorId = current.getReplacedByTokenId();
            if (successorId == null || ids.contains(successorId)) {
                break;
            }
            current = refreshTokenRepository.findById(successorId).orElse(null);
        }
        return ids;
    }

    private RefreshToken walkToRoot(RefreshToken member) {
        Set<Long> visited = new HashSet<>();
        RefreshToken current = member;
        while (visited.add(current.getId()) && visited.size() < MAX_FAMILY_SIZE) {
            Optional<RefreshToken> predecessor = refreshTokenRepository.findByReplacedByTokenId(current.getId());
            if (predecessor.isEmpty() || visited.contains(predecessor.get().getId())) {
                return current;
            }
            current = predecessor.get();
        }
        return current;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return tokenEncoder.encodeToString(bytes);
    }

    /**
     * SHA-256 hex of the raw token. A plain digest (not BCrypt) is correct here: the
     * input is 256 bits of uniform randomness, so there is nothing to brute-force, and
     * lookup by digest has to be a single indexed equality match.
     */
    private String hash(String rawToken) {
        if (rawToken == null) {
            throw new InvalidRefreshTokenException();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
