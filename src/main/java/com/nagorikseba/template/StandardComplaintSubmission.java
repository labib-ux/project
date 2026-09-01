package com.nagorikseba.template;

import com.nagorikseba.dto.complaint.ComplaintSubmissionRequest;
import com.nagorikseba.entity.Attachment;
import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.User;
import com.nagorikseba.enums.AttachmentType;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.repository.AttachmentRepository;
import com.nagorikseba.repository.ComplaintRepository;
import com.nagorikseba.shared.config.StorageProperties;
import com.nagorikseba.shared.service.FileStorageService;
import com.nagorikseba.service.SlaService;
import com.nagorikseba.state.ComplaintAction;
import com.nagorikseba.state.ComplaintStateMachine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class StandardComplaintSubmission extends ComplaintSubmissionTemplate {

    private final StorageProperties storageProperties;
    private final FileStorageService fileStorageService;
    private final AttachmentRepository attachmentRepository;
    private final ComplaintStateMachine stateMachine;
    private final SlaService slaService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public StandardComplaintSubmission(ComplaintRepository complaintRepository, WardRepository wardRepository,
            StorageProperties storageProperties, FileStorageService fileStorageService,
            AttachmentRepository attachmentRepository, ComplaintStateMachine stateMachine,
            SlaService slaService, org.springframework.context.ApplicationEventPublisher eventPublisher) {
        super(complaintRepository, wardRepository);
        this.storageProperties = storageProperties;
        this.fileStorageService = fileStorageService;
        this.attachmentRepository = attachmentRepository;
        this.stateMachine = stateMachine;
        this.slaService = slaService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void validate(ComplaintSubmissionRequest request, User citizen) {
        if (request.getPhotos().size() > storageProperties.getMaxPhotosPerComplaint()) {
            throw new IllegalArgumentException(
                    "You can upload up to " + storageProperties.getMaxPhotosPerComplaint() + " photos");
        }
    }

    @Override
    protected void saveAttachments(Complaint complaint, ComplaintSubmissionRequest request) {
        List<FileStorageService.StoredFile> storedFiles = new ArrayList<>();
        try {
            for (var photo : request.getPhotos()) {
                storedFiles.add(fileStorageService.storeIssuePhoto(photo));
            }
            List<Attachment> attachments = storedFiles.stream()
                    .map(file -> Attachment.builder()
                            .complaint(complaint)
                            .fileUrl(file.publicUrl())
                            .fileType(AttachmentType.IMAGE)
                            .uploadedBy(complaint.getCitizen())
                            .workProof(false)
                            .build())
                    .toList();
            attachmentRepository.saveAllAndFlush(attachments);
        } catch (RuntimeException e) {
            fileStorageService.deleteAll(storedFiles);
            throw e;
        }
    }

    @Override
    protected void afterSubmit(Complaint complaint) {
        complaint.setDeadlineAt(slaService.calculateDeadline(complaint.getCategory(), Priority.NORMAL));
        complaintRepository.saveAndFlush(complaint);

        eventPublisher.publishEvent(new com.nagorikseba.event.ComplaintStatusChangedEvent(
                this, complaint, null, com.nagorikseba.enums.ComplaintStatus.SUBMITTED, complaint.getCitizen(),
                "Complaint submitted by citizen"));
    }
}
