package com.nagorikseba.repository;

import com.nagorikseba.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByComplaintIdOrderByUploadedAtAsc(Long complaintId);
}
