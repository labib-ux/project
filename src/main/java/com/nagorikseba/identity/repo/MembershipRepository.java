package com.nagorikseba.identity.repo;

import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Active staff currently posted to one municipality, for any of {@code roles}.
     *
     * <p>This is the reverse of {@link #findByUserIdAndValidUntilIsNull(Long)}: given
     * the municipality a complaint landed in, which officers and councillors should
     * hear about it. {@code distinct} because a user may hold several postings in the
     * same municipality (one per department), and {@code m.user.active} because a
     * deactivated account must never be woken up by a new report.
     */
    @Query("""
            select distinct m.user from UserMunicipalityMembership m
            where m.municipality.id = :municipalityId
              and m.validUntil is null
              and m.user.active = true
              and m.user.role in :roles
            """)
    List<User> findActiveStaffServingMunicipality(@Param("municipalityId") Long municipalityId,
                                                  @Param("roles") Collection<UserRole> roles);
}
