package com.nagorikseba.complaint.submission;

import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class AnonymousComplaintSubmission extends ComplaintSubmissionTemplate {

    private final MunicipalityRepository municipalityRepository;
    private final PrincipalContext principalContext;

    @Override
    protected void validate(ComplaintSubmissionRequest request, User citizen) {
        super.validate(request, citizen);
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("Phone number is required for anonymous complaints");
        }
    }

    @Override
    protected Municipality resolveMunicipality(ComplaintSubmissionRequest request, User citizen) {
        // For anonymous, municipality is resolved from location
        // This is a bbox fallback - full PostGIS spatial query comes in Phase 4
        return municipalityRepository.findFirstByIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active municipality found"));
    }

    @Override
    protected void afterSubmit(Complaint complaint) {
        super.afterSubmit(complaint);
        complaint.setModerationStatus(ModerationStatus.PENDING);
        complaint.setPriority(Priority.LOW);
    }
}