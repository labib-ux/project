package com.nagorikseba.complaint.repo;

import com.nagorikseba.complaint.domain.ComplaintTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintTransitionRepository extends JpaRepository<ComplaintTransition, Long> {

    /** The citizen-facing timeline, oldest first. */
    List<ComplaintTransition> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);

    /**
     * Backs the R3 replay check. Returns the original transition so the service can
     * answer a retried request with the outcome it already produced, rather than
     * applying the action a second time or rejecting the retry as a conflict.
     * Uniqueness is enforced in the schema by {@code uq_transition_idempotency}.
     */
    Optional<ComplaintTransition> findByComplaintIdAndIdempotencyKey(Long complaintId, String idempotencyKey);

    boolean existsByComplaintIdAndIdempotencyKey(Long complaintId, String idempotencyKey);

    long countByComplaintId(Long complaintId);
}
