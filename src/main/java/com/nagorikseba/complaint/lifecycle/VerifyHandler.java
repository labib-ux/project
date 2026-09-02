package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * SUBMITTED → VERIFIED. A ward councilor confirms the complaint is real (§6).
 *
 * <p>{@code firstVerifiedAt} is stamped once and never overwritten — it anchors the
 * SLA clock, so a later re-verification after a reopen must not reset it.
 */
@Component
public class VerifyHandler extends ComplaintMutator implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.VERIFY;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.SUBMITTED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        changeStatus(complaint, ComplaintStatus.VERIFIED);
        markFirstVerifiedAt(complaint, occurredAt);
        // A verified complaint is confirmed real, so it is safe to show on the public map.
        changeModerationStatus(complaint, com.nagorikseba.complaint.domain.enums.ModerationStatus.APPROVED);
    }
}
