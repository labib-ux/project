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
}