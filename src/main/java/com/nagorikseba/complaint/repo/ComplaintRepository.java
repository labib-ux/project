package com.nagorikseba.complaint.repo;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    /**
     * {@code SELECT … FOR UPDATE} — the serialization point for every state change.
     *
     * <p>Concurrent transitions on the same complaint queue here instead of
     * interleaving, which is what lets the version check that follows be a real
     * guard: the second caller reads the row only after the first has committed, so
     * it sees the bumped version and is refused with 409 rather than overwriting.
     */
    @Lock(PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Complaint c WHERE c.id = :id")
    Optional<Complaint> findAndLockById(@Param("id") Long id);

    Optional<Complaint> findByReferenceCode(String referenceCode);

    /** R3 — a replayed submission resolves to the complaint its key already created. */
    Optional<Complaint> findBySubmissionIdempotencyKey(String submissionIdempotencyKey);

    List<Complaint> findByCitizenIdOrderBySubmittedAtDesc(Long citizenId);

    List<Complaint> findByMunicipalityIdAndStatusIn(Long municipalityId, List<ComplaintStatus> statuses);

    Page<Complaint> findByMunicipalityIdAndStatusIn(Long municipalityId, List<ComplaintStatus> statuses, Pageable pageable);

    List<Complaint> findByWardIdAndStatusNotIn(Long wardId, List<ComplaintStatus> statuses);

    List<Complaint> findByAssignedOfficerIdAndStatusIn(Long officerId, List<ComplaintStatus> statuses);

    /**
     * Dashboard per-ward counts (§4, Phase 4): one row per (ward, status) backed
     * by {@code idx_complaint_ward_status}. Ward id is null for complaints whose
     * pin fell outside every known boundary.
     */
    @Query("""
            SELECT c.ward.id, c.status, COUNT(c) FROM Complaint c
            WHERE c.municipality.id = :municipalityId
            GROUP BY c.ward.id, c.status
            """)
    List<Object[]> countByMunicipalityGroupByWardAndStatus(@Param("municipalityId") Long municipalityId);

    /**
     * Mean hours from submission to resolution for resolved complaints of one
     * municipality (the dashboard "avg resolution time" card).
     */
    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - submitted_at)) / 3600.0)
            FROM complaints
            WHERE municipality_id = :municipalityId
              AND resolved_at IS NOT NULL
            """, nativeQuery = true)
    Double averageResolutionHoursByMunicipality(@Param("municipalityId") Long municipalityId);
}