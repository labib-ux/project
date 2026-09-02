package com.nagorikseba.complaint.service;

import com.nagorikseba.complaint.api.dto.ComplaintResponse;
import com.nagorikseba.complaint.api.dto.AttachmentResponse;
import com.nagorikseba.complaint.api.dto.TransitionResponse;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ComplaintMapper {

    private final PrincipalContext principalContext;

    public ComplaintResponse toResponse(Complaint complaint, List<Attachment> attachments, List<ComplaintTransition> transitions) {
        boolean isOwner = isOwner(complaint);
        boolean isAuthority = principalContext.servesMunicipality(complaint.getMunicipality().getId());

        ComplaintResponse response = ComplaintResponse.builder()
                .id(complaint.getId())
                .referenceCode(complaint.getReferenceCode())
                .title(complaint.getTitle())
                .description(complaint.getDescription())
                .category(complaint.getCategory())
                .status(complaint.getStatus())
                .priority(complaint.getPriority())
                .latitude(complaint.getLocation() != null ? complaint.getLocation().getY() : null)
                .longitude(complaint.getLocation() != null ? complaint.getLocation().getX() : null)
                .addressText(complaint.getAddressText())
                .wardId(complaint.getWard() != null ? complaint.getWard().getId() : null)
                .wardName(complaint.getWard() != null ? complaint.getWard().getAreaName() : null)
                .municipalityId(complaint.getMunicipality() != null ? complaint.getMunicipality().getId() : null)
                .municipalityName(complaint.getMunicipality() != null ? complaint.getMunicipality().getName() : null)
                .citizenName(isOwner || isAuthority ? (complaint.getCitizen() != null ? complaint.getCitizen().getFullName() : "Anonymous") : null)
                .citizenPhone(isOwner || isAuthority ? (complaint.getCitizen() != null ? complaint.getCitizen().getPhone() : complaint.getAnonymousContactPhone()) : null)
                .submittedAt(complaint.getSubmittedAt())
                .firstVerifiedAt(complaint.getFirstVerifiedAt())
                .resolvedAt(complaint.getResolvedAt())
                .closedAt(complaint.getClosedAt())
                .reopenCount(complaint.getReopenCount())
                .rejectionReason(complaint.getRejectionReason())
                .cancellationReason(complaint.getCancellationReason())
                .publicVisible(complaint.isPublicVisible())
                .moderationStatus(complaint.getModerationStatus())
                .attachments(attachments.stream().map(this::toAttachmentResponse).toList())
                .timeline(transitions.stream().map(this::toTransitionResponse).toList())
                .build();

        return response;
    }

    private boolean isOwner(Complaint complaint) {
        Long userId = principalContext.currentUserId().orElse(null);
        return userId != null && complaint.getCitizen() != null && complaint.getCitizen().getId().equals(userId);
    }

    private AttachmentResponse toAttachmentResponse(Attachment attachment) {
        return AttachmentResponse.builder()
                .id(attachment.getId())
                .storageKey(attachment.getStorageKey())
                .originalFilename(attachment.getOriginalFilename())
                .contentType(attachment.getContentType())
                .byteSize(attachment.getByteSize())
                .workProof(attachment.isWorkProof())
                .scanStatus(attachment.getScanStatus())
                .createdAt(attachment.getCreatedAt())
                .build();
    }

    private TransitionResponse toTransitionResponse(ComplaintTransition transition) {
        return TransitionResponse.builder()
                .id(transition.getId())
                .fromStatus(transition.getFromStatus())
                .toStatus(transition.getToStatus())
                .action(transition.getAction().name())
                .actorName(transition.getActor() != null ? transition.getActor().getFullName() : "System")
                .actorRole(transition.getActorRole())
                .note(transition.getNote())
                .createdAt(transition.getCreatedAt())
                .build();
    }
}