package com.nagorikseba.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SlaInstanceRepository extends JpaRepository<SlaInstance, Long> {

    Optional<SlaInstance> findByComplaintId(Long complaintId);
}
