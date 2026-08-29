package com.nagorikseba.dto.complaint;

import com.nagorikseba.enums.AttachmentType;

import java.time.LocalDateTime;

public record AttachmentResponse(
        Long id,
        String fileUrl,
        AttachmentType fileType,
        boolean workProof,
        LocalDateTime uploadedAt
) {
}
