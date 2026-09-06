package com.nagorikseba.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SlaBreachRepository extends JpaRepository<SlaBreach, Long> {

    Optional<SlaBreach> findByComplaintIdAndResolvedAtIsNull(Long complaintId);

    List<SlaBreach> findByComplaintIdOrderByDetectedAtAsc(Long complaintId);

    long countByComplaintIdAndResolvedAtIsNull(Long complaintId);
}
