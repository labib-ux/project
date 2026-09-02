package com.nagorikseba.complaint.api.dto;

import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintResponse {

    private Long id;
    private String referenceCode;
    private String title;
    private String description;
    private Category category;
    private ComplaintStatus status;
    private Priority priority;
    private Double latitude;
    private Double longitude;
    private String addressText;
    private Long wardId;
    private String wardName;
    private Long municipalityId;
    private String municipalityName;
    private String citizenName;
    private String citizenPhone;
    private Instant submittedAt;
    private Instant firstVerifiedAt;
    private Instant resolvedAt;
    private Instant closedAt;
    private int reopenCount;
    private String rejectionReason;
    private String cancellationReason;
    private boolean publicVisible;
    private ModerationStatus moderationStatus;
    private List<AttachmentResponse> attachments;
    private List<TransitionResponse> timeline;
}