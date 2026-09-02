package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CancelHandler implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.CANCEL;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command) {
        if (command.note() == null || command.note().isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }

        if (!complaint.getCitizen().getId().equals(command.actorId())) {
            throw new IllegalArgumentException("Only the complainant can cancel the complaint");
        }

        complaint.setStatus(ComplaintStatus.CANCELLED);
        complaint.setCancellationReason(command.note());
        complaint.setPublicVisible(false);
    }
}