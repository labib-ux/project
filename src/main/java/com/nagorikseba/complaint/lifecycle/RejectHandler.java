package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * SUBMITTED → REJECTED. An authority judges the complaint invalid (§6).
 *
 * <p>A reason is mandatory: rejection is the one outcome a citizen cannot appeal
 * without knowing why, and an unexplained rejection is how a complaints system
 * loses public trust. It is also pulled off the public map — a rejected report
 * should not keep signalling a problem that was found not to exist.
 */
@Component
public class RejectHandler extends ComplaintMutator implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.REJECT;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.SUBMITTED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        if (command.note() == null || command.note().isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required");
        }
        changeStatus(complaint, ComplaintStatus.REJECTED);
        recordRejection(complaint, command.note().trim());
        changePublicVisibility(complaint, false);
        changeModerationStatus(complaint, ModerationStatus.REJECTED);
    }
}
