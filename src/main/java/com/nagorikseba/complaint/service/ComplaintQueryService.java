package com.nagorikseba.complaint.service;

import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.complaint.repo.AttachmentRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComplaintQueryService {

    private final ComplaintRepository complaintRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final AttachmentRepository attachmentRepository;
    private final com.nagorikseba.complaint.service.ComplaintMapper mapper;
    private final PrincipalContext principalContext;

    public ComplaintResponse findByReferenceCode(String referenceCode) {
        Complaint complaint = complaintRepository.findByReferenceCode(referenceCode)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + referenceCode));
        
        if (!canAccess(complaint)) {
            throw new ResourceNotFoundException("Complaint not found: " + referenceCode);
        }
        
        return toResponse(complaint);
    }

    /**
     * Render a complaint the caller has already been granted — the one they just
     * submitted, or the one a lifecycle action just returned.
     *
     * <p>Skips {@link #canAccess} on purpose: an anonymous submitter has no
     * principal to match against, so the ownership check would answer 404 for the
     * complaint they created one millisecond earlier. Callers must only pass a
     * complaint whose access they have already established.
     */
    public ComplaintResponse describe(Complaint complaint) {
        // The complaint passed in is detached (its submitting transaction already
        // committed), so lazy associations (municipality/ward/citizen) cannot load
        // through it. Re-fetch inside this read-only transaction to attach it.
        Complaint attached = complaint.getId() != null
                ? complaintRepository.findById(complaint.getId()).orElse(complaint)
                : complaint;
        return toResponse(attached);
    }

    public List<ComplaintResponse> findMyComplaints() {
        Long citizenId = principalContext.requireUserId();
        List<Complaint> complaints = complaintRepository.findByCitizenIdOrderBySubmittedAtDesc(citizenId);
        return complaints.stream().map(this::toResponse).toList();
    }

    public Page<ComplaintResponse> findAuthorityComplaints(Long municipalityId, Set<ComplaintStatus> statuses, Pageable pageable) {
        principalContext.requireMunicipality(municipalityId);
        Page<Complaint> page = complaintRepository.findByMunicipalityIdAndStatusIn(municipalityId, List.copyOf(statuses), pageable);
        return page.map(this::toResponse);
    }

    private boolean canAccess(Complaint complaint) {
        if (principalContext.isAdmin()) {
            return true;
        }
        Long userId = principalContext.currentUserId().orElse(null);
        if (userId == null) {
            return false;
        }
        if (complaint.getCitizen() != null && complaint.getCitizen().getId().equals(userId)) {
            return true;
        }
        return principalContext.servesMunicipality(complaint.getMunicipality().getId());
    }

    private ComplaintResponse toResponse(Complaint complaint) {
        List<com.nagorikseba.complaint.domain.Attachment> attachments = 
                attachmentRepository.findByComplaintIdAndDeletedAtIsNullOrderByCreatedAtAsc(complaint.getId());
        List<com.nagorikseba.complaint.domain.ComplaintTransition> transitions = 
                transitionRepository.findByComplaintIdOrderByCreatedAtAsc(complaint.getId());
        return mapper.toResponse(complaint, attachments, transitions);
    }
}