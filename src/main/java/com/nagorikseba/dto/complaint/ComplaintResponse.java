package com.nagorikseba.dto.complaint;

import com.nagorikseba.enums.ComplaintCategory;
import com.nagorikseba.enums.ComplaintStatus;
import com.nagorikseba.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ComplaintResponse(
        Long id,
        String title,
        String description,
        ComplaintCategory category,
        ComplaintStatus status,
        Priority priority,
        BigDecimal latitude,
        BigDecimal longitude,
        Long wardId,
        String wardName,
        LocalDateTime submittedAt,
        LocalDateTime deadlineAt,
        List<AttachmentResponse> attachments,
        List<StatusUpdateResponse> timeline
) {
}
