package com.nagorikseba.repository;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    List<Complaint> findByCitizenIdOrderBySubmittedAtDesc(Long citizenId);

    List<Complaint> findByWardIdOrderBySubmittedAtDesc(Long wardId);

    List<Complaint> findByStatusNotInAndDeadlineAtBefore(Collection<ComplaintStatus> statuses, LocalDateTime deadline);
}
