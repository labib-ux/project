package com.nagorikseba.identity.repo;

import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Blueprint I6 — current-membership lookups.
 *
 * <p>"Current" always means {@code valid_until IS NULL}, which is exactly the
 * predicate behind the {@code idx_membership_user_current} partial index.
 */
public interface MembershipRepository extends JpaRepository<UserMunicipalityMembership, Long> {

    /** Current postings of a user, with municipality/ward/department associations. */
    @EntityGraph(attributePaths = {"municipality", "ward", "department"})
    List<UserMunicipalityMembership> findByUserIdAndValidUntilIsNull(Long userId);

    /** Municipality ids for the JWT {@code mids} claim — projection, no entity load. */
    @Query("""
            select m.municipality.id from UserMunicipalityMembership m
            where m.user.id = :userId
              and m.validUntil is null
            order by m.municipality.id
            """)
    List<Long> findCurrentMunicipalityIds(@Param("userId") Long userId);

    boolean existsByUserIdAndMunicipalityIdAndValidUntilIsNull(Long userId, Long municipalityId);
}
