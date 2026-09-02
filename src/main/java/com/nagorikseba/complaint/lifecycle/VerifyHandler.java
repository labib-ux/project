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
public class VerifyHandler implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.VERIFY;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.SUBMITTED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command) {
        User actor = command.actorId() != null
                ? new User() {{ setId(command.actorId()); }}
                : null;

        complaint.setStatus(ComplaintStatus.VERIFIED);
        complaint.setFirstVerifiedAt(Instant.now());
    }
}