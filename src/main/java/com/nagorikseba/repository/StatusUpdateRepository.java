package com.nagorikseba.repository;

import com.nagorikseba.entity.StatusUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatusUpdateRepository extends JpaRepository<StatusUpdate, Long> {

    List<StatusUpdate> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);
}
