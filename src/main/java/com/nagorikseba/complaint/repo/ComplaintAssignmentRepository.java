package com.nagorikseba.complaint.repo;

import com.nagorikseba.complaint.domain.ComplaintAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintAssignmentRepository extends JpaRepository<ComplaintAssignment, Long> {

    /** Full chain for one complaint, oldest first (reassignment analytics). */
    List<ComplaintAssignment> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);

    /** The current assignment, if any — backed by {@code idx_assignment_complaint_active}. */
    Optional<ComplaintAssignment> findByComplaintIdAndUnassignedAtIsNull(Long complaintId);

    /** Active workload of one officer — the load-balancing input (§7.2). */
    long countByOfficerIdAndUnassignedAtIsNull(Long officerId);

    /** Active workload of one department. */
    long countByDepartmentIdAndUnassignedAtIsNull(Long departmentId);
}
