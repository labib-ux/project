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

    @Lock(PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Complaint c WHERE c.id = :id")
    Optional<Complaint> findAndLockById(@Param("id") Long id);

    Optional<Complaint> findByReferenceCode(String referenceCode);

    List<Complaint> findByCitizenIdOrderBySubmittedAtDesc(Long citizenId);

    List<Complaint> findByMunicipalityIdAndStatusIn(Long municipalityId, List<ComplaintStatus> statuses);

    List<Complaint> findByWardIdAndStatusNotIn(Long wardId, List<ComplaintStatus> statuses);

    List<Complaint> findByAssignedOfficerIdAndStatusIn(Long officerId, List<ComplaintStatus> statuses);
}