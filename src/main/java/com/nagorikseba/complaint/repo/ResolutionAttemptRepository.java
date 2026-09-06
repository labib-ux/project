package com.nagorikseba.complaint.repo;

import com.nagorikseba.complaint.domain.ResolutionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResolutionAttemptRepository extends JpaRepository<ResolutionAttempt, Long> {

    List<ResolutionAttempt> findByComplaintIdOrderByAttemptNumberAsc(Long complaintId);

    Optional<ResolutionAttempt> findFirstByComplaintIdOrderByAttemptNumberDesc(Long complaintId);

    long countByComplaintId(Long complaintId);
}
