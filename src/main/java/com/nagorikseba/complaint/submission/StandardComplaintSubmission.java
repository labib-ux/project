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
 * Submission for an authenticated citizen.
 *
 * <p>Differs from {@link AnonymousComplaintSubmission} in two ways: it never
 * requires a phone number (the citizen's account already carries contact details,
 * and reporting a pothole should not demand a second phone), and it ships with a
 * default {@code moderation_status} of APPROVED — a signed-in report is
 * attributable and can be trusted by default. Anonymous reports stay PENDING.
 *
 * <p>Municipality and ward are resolved by the spatial lookup in the template, not
 * from a membership: citizens hold no municipality membership rows, so any
 * authority-scoped derivation would reject every real submission.
 */
@Component
public class StandardComplaintSubmission extends ComplaintSubmissionTemplate {

    public StandardComplaintSubmission(ComplaintRepository complaintRepository,
                                       WardRepository wardRepository,
                                       MunicipalityRepository municipalityRepository,
                                       AttachmentService attachmentService,
                                       ComplaintLifecycleService lifecycleService,
                                       Clock clock) {
        super(complaintRepository, wardRepository, municipalityRepository,
                attachmentService, lifecycleService, clock);
    }

    @Override
    protected ModerationStatus initialModerationStatus() {
        return ModerationStatus.APPROVED;
    }
}
