package com.nagorikseba.dto.complaint;

import com.nagorikseba.enums.ComplaintStatus;

import java.time.LocalDateTime;

public record StatusUpdateResponse(
        Long id,
        ComplaintStatus fromStatus,
        ComplaintStatus toStatus,
        String note,
        String updatedBy,
        LocalDateTime createdAt
) {
}
