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
public class RejectHandler implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.REJECT;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.SUBMITTED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command) {
        if (command.note() == null || command.note().isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }

        complaint.setStatus(ComplaintStatus.REJECTED);
        complaint.setRejectionReason(command.note());
        complaint.setPublicVisible(false);
    }
}