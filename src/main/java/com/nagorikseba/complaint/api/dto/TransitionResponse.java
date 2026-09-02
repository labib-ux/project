package com.nagorikseba.complaint.api.dto;

import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitionResponse {
    private Long id;
    private ComplaintStatus fromStatus;
    private ComplaintStatus toStatus;
    private String action;
    private String actorName;
    private String actorRole;
    private String note;
    private Instant createdAt;
}