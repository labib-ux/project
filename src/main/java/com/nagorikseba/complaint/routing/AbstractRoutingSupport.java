package com.nagorikseba.complaint.routing;

import com.nagorikseba.complaint.repo.ComplaintAssignmentRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Comparator;
import java.util.List;

/**
 * Shared reads for the routing strategies (§7.2).
 *
 * <p>Officers are resolved through current membership rows (the authoritative
 * tenancy record since Phase 3), not the retained {@code users.department_id}
 * column: posting history is what says who serves where today. Membership
 * queries run as explicit JPQL here so neither {@code MembershipRepository}
 * nor {@code UserRepository} needs a Phase-4 finder added.
 */
public abstract class AbstractRoutingSupport implements ComplaintRoutingStrategy {

    @PersistenceContext
    protected EntityManager entityManager;

    protected final ComplaintAssignmentRepository assignmentRepository;

    protected AbstractRoutingSupport(ComplaintAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Active officers currently posted to a department, lowest user id first
     * (the deterministic tie-break base for every strategy).
     */
    protected List<User> officersOfDepartment(Long departmentId) {
        return entityManager.createQuery("""
                select m.user from UserMunicipalityMembership m
                where m.department.id = :departmentId
                  and m.validUntil is null
                  and m.user.active = true
                order by m.user.id asc
                """, User.class)
                .setParameter("departmentId", departmentId)
                .getResultList();
    }

    /**
     * Active officers posted anywhere in a municipality, lowest user id first.
     *
     * <p>No DISTINCT: Postgres rejects {@code ORDER BY} expressions absent from
     * a DISTINCT select list, and duplicates are harmless downstream —
     * {@link #leastLoaded} picks the minimum, which is duplicate-insensitive,
     * so the lowest-id tie-break holds either way.
     */
    protected List<User> officersOfMunicipality(Long municipalityId) {
        return entityManager.createQuery("""
                select m.user from UserMunicipalityMembership m
                where m.municipality.id = :municipalityId
                  and m.department is not null
                  and m.validUntil is null
                  and m.user.active = true
                order by m.user.id asc
                """, User.class)
                .setParameter("municipalityId", municipalityId)
                .getResultList();
    }

    /** Current open-assignment count for one officer (the load-balancing input). */
    protected long activeLoad(Long officerId) {
        return assignmentRepository.countByOfficerIdAndUnassignedAtIsNull(officerId);
    }

    /**
     * Least-loaded officer, ties broken by lowest user id. The input list must
     * already be id-ordered for the tie-break to hold.
     */
    protected User leastLoaded(List<User> officers) {
        return officers.stream()
                .min(Comparator.comparingLong((User officer) -> activeLoad(officer.getId()))
                        .thenComparingLong(User::getId))
                .orElse(null);
    }

    /** The department an officer is currently posted to, if any. */
    protected Department postingDepartment(Long officerId, Long municipalityId) {
        List<Department> departments = entityManager.createQuery("""
                select m.department from UserMunicipalityMembership m
                where m.user.id = :officerId
                  and m.municipality.id = :municipalityId
                  and m.department is not null
                  and m.validUntil is null
                order by m.department.id asc
                """, Department.class)
                .setParameter("officerId", officerId)
                .setParameter("municipalityId", municipalityId)
                .getResultList();
        return departments.isEmpty() ? null : departments.get(0);
    }
}
