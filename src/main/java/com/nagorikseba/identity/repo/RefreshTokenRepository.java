package com.nagorikseba.identity.repo;

import com.nagorikseba.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Blueprint I5 — refresh-token persistence with family traversal (R9).
 *
 * <p>A "family" is one login session's rotation chain, linked by
 * {@code replaced_by_token_id}. Presenting a token that was already rotated away
 * means the token leaked, so the entire chain is revoked and the user must log in
 * again.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Predecessor lookup — used to walk a family backwards towards its root. */
    Optional<RefreshToken> findByReplacedByTokenId(Long replacedByTokenId);

    /**
     * Live family heads for a user: tokens that are neither revoked nor expired.
     * One row per concurrent session (browser, phone, …).
     */
    @Query("""
            select t from RefreshToken t
            where t.user.id = :userId
              and t.revokedAt is null
              and t.expiresAt > :now
            order by t.id
            """)
    List<RefreshToken> findActiveTokenFamilies(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Revokes every still-live token in a family in one statement. Bulk update so
     * a compromised family dies immediately, without loading or version-checking
     * each row.
     *
     * @return number of tokens actually revoked by this call
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now
             where t.id in :tokenIds
               and t.revokedAt is null
            """)
    int revokeFamily(@Param("tokenIds") Collection<Long> tokenIds, @Param("now") Instant now);
}
