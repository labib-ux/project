package com.nagorikseba.complaint.submission;

import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.AttachmentService;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Submission with no account behind it.
 *
 * <p>Anonymous reporting exists so that someone can report a problem they would
 * not put their name to — an illegal dump behind a councillor's property, say.
 * That protection comes with two costs, both applied here:
 *
 * <ul>
 *   <li>a contact phone is <strong>required</strong>, because there is no account
 *       to reach and a report nobody can follow up on cannot be verified;</li>
 *   <li>the complaint starts {@code PENDING} moderation and {@code LOW} priority,
 *       so unattributable reports are seen by a moderator before they are public
 *       and cannot be used to jump the queue.</li>
 * </ul>
 */
@Component
public class AnonymousComplaintSubmission extends ComplaintSubmissionTemplate {

    public AnonymousComplaintSubmission(ComplaintRepository complaintRepository,
                                        WardRepository wardRepository,
                                        MunicipalityRepository municipalityRepository,
                                        AttachmentService attachmentService,
                                        ComplaintLifecycleService lifecycleService,
                                        Clock clock) {
        super(complaintRepository, wardRepository, municipalityRepository,
                attachmentService, lifecycleService, clock);
    }

    @Override
    protected void validate(ComplaintSubmissionRequest request, User citizen) {
        super.validate(request, citizen);
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("A contact phone number is required for anonymous complaints");
        }
    }

    @Override
    protected String anonymousContactPhone(ComplaintSubmissionRequest request, User citizen) {
        return request.getPhone().trim();
    }

    @Override
    protected ModerationStatus initialModerationStatus() {
        return ModerationStatus.PENDING;
    }

    @Override
    protected Priority initialPriority() {
        return Priority.LOW;
    }
}
