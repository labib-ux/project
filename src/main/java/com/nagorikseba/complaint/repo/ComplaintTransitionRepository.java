package com.nagorikseba.complaint.repo;

import com.nagorikseba.complaint.domain.ComplaintTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplaintTransitionRepository extends JpaRepository<ComplaintTransition, Long> {

    List<ComplaintTransition> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);

    boolean existsByComplaintIdAndIdempotencyKey(Long complaintId, String idempotencyKey);
}