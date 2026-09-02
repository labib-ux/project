package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.enums.ComplaintAction;

import java.util.List;

public record TransitionCommand(
        ComplaintAction action,
        Long complaintId,
        Long actorId,
        String note,
        List<Long> evidenceAttachmentIds,
        String idempotencyKey,
        int expectedVersion
) {
}