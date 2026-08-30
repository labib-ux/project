package com.nagorikseba.service;

import com.nagorikseba.config.StorageProperties;
import com.nagorikseba.dto.complaint.AttachmentResponse;
import com.nagorikseba.dto.complaint.ComplaintResponse;
import com.nagorikseba.dto.complaint.ComplaintSubmissionRequest;
import com.nagorikseba.dto.complaint.StatusUpdateResponse;
import com.nagorikseba.entity.Attachment;
import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.StatusUpdate;
import com.nagorikseba.entity.User;
import com.nagorikseba.enums.AttachmentType;
import com.nagorikseba.enums.ComplaintStatus;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.exception.ResourceNotFoundException;
import com.nagorikseba.repository.AttachmentRepository;
import com.nagorikseba.repository.ComplaintRepository;
import com.nagorikseba.repository.StatusUpdateRepository;
import com.nagorikseba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final AttachmentRepository attachmentRepository;
    private final StatusUpdateRepository statusUpdateRepository;
    private final UserRepository userRepository;
    private final com.nagorikseba.repository.WardRepository wardRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final com.nagorikseba.template.StandardComplaintSubmission standardComplaintSubmission;

    @Transactional
    public ComplaintResponse submit(ComplaintSubmissionRequest request, String citizenEmail) {
        User citizen = userRepository.findByEmailIgnoreCase(citizenEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Citizen account not found"));
        if (citizen.getRole() != UserRole.CITIZEN) {
            throw new IllegalArgumentException("Only citizens can submit complaints");
        }

        Complaint complaint = standardComplaintSubmission.processSubmission(request, citizen);

        return toResponse(complaint);
    }

    @Transactional(readOnly = true)
    public List<ComplaintResponse> findMyComplaints(String citizenEmail) {
        User citizen = userRepository.findByEmailIgnoreCase(citizenEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Citizen account not found"));
        return complaintRepository.findByCitizenIdOrderBySubmittedAtDesc(citizen.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ComplaintResponse findMyComplaint(Long complaintId, String citizenEmail) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));
        if (!complaint.getCitizen().getEmail().equalsIgnoreCase(citizenEmail)) {
            throw new ResourceNotFoundException("Complaint not found");
        }
        return toResponse(complaint);
    }

    private ComplaintResponse toResponse(Complaint complaint) {
        return toResponse(
                complaint,
                attachmentRepository.findByComplaintIdOrderByUploadedAtAsc(complaint.getId()),
                statusUpdateRepository.findByComplaintIdOrderByCreatedAtAsc(complaint.getId()));
    }

    private ComplaintResponse toResponse(Complaint complaint, List<Attachment> attachments,
            List<StatusUpdate> timeline) {
        return new ComplaintResponse(
                complaint.getId(),
                complaint.getTitle(),
                complaint.getDescription(),
                complaint.getCategory(),
                complaint.getStatus(),
                complaint.getPriority(),
                complaint.getLatitude(),
                complaint.getLongitude(),
                complaint.getWard() == null ? null : complaint.getWard().getId(),
                complaint.getWard() == null ? null : complaint.getWard().getAreaName(),
                complaint.getSubmittedAt(),
                complaint.getDeadlineAt(),
                attachments.stream().map(this::toAttachmentResponse).toList(),
                timeline.stream()
                        .sorted(Comparator.comparing(StatusUpdate::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(this::toStatusUpdateResponse)
                        .toList());
    }

    private AttachmentResponse toAttachmentResponse(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(), attachment.getFileUrl(), attachment.getFileType(),
                attachment.isWorkProof(), attachment.getUploadedAt());
    }

    private void cleanUpFilesIfTransactionRollsBack(List<FileStorageService.StoredFile> storedFiles) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorageService.deleteAll(storedFiles);
                }
            }
        });
    }

    private StatusUpdateResponse toStatusUpdateResponse(StatusUpdate update) {
        return new StatusUpdateResponse(
                update.getId(), update.getFromStatus(), update.getToStatus(), update.getNote(),
                update.getUpdatedBy() == null ? null : update.getUpdatedBy().getFullName(), update.getCreatedAt());
    }
}
