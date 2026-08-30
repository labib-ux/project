package com.nagorikseba.template;

import com.nagorikseba.dto.complaint.ComplaintSubmissionRequest;
import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.User;
import com.nagorikseba.entity.Ward;
import com.nagorikseba.enums.ComplaintStatus;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.repository.ComplaintRepository;
import com.nagorikseba.repository.WardRepository;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@RequiredArgsConstructor
public abstract class ComplaintSubmissionTemplate {

    protected final ComplaintRepository complaintRepository;
    protected final WardRepository wardRepository;

    public final Complaint processSubmission(ComplaintSubmissionRequest request, User citizen) {
        validate(request, citizen);
        Complaint complaint = createComplaint(request, citizen);
        complaint = complaintRepository.saveAndFlush(complaint);
        saveAttachments(complaint, request);
        afterSubmit(complaint);
        return complaint;
    }

    protected abstract void validate(ComplaintSubmissionRequest request, User citizen);
    protected abstract void afterSubmit(Complaint complaint);
    protected abstract void saveAttachments(Complaint complaint, ComplaintSubmissionRequest request);

    private Complaint createComplaint(ComplaintSubmissionRequest request, User citizen) {
        Ward detectedWard = null;
        if (request.getLatitude() != null && request.getLongitude() != null) {
            detectedWard = wardRepository.findWardByCoordinates(request.getLatitude(), request.getLongitude()).orElse(null);
        }

        return Complaint.builder()
                .title(request.getTitle().trim())
                .description(request.getDescription().trim())
                .category(request.getCategory())
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .ward(detectedWard)
                .citizen(citizen)
                .build();
    }
}
