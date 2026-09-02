package com.nagorikseba.complaint.submission;

import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.repo.MunicipalityRepository;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class StandardComplaintSubmission extends ComplaintSubmissionTemplate {

    private final MunicipalityRepository municipalityRepository;
    private final PrincipalContext principalContext;

    @Override
    protected Municipality resolveMunicipality(ComplaintSubmissionRequest request, User citizen) {
        Long userId = principalContext.requireUserId();
        Set<Long> municipalityIds = principalContext.municipalityIds();
        if (municipalityIds.isEmpty()) {
            throw new IllegalStateException("Citizen has no municipality membership");
        }
        Long municipalityId = municipalityIds.iterator().next();
        return municipalityRepository.findById(municipalityId)
                .orElseThrow(() -> new IllegalStateException("Municipality not found: " + municipalityId));
    }

    @Override
    protected void afterSubmit(Complaint complaint) {
        super.afterSubmit(complaint);
        complaint.setModerationStatus(ModerationStatus.APPROVED);
    }
}