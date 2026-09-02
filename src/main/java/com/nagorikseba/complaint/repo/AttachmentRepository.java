package com.nagorikseba.complaint.repo;

import com.nagorikseba.complaint.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByComplaintIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long complaintId);

    List<Attachment> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);
}