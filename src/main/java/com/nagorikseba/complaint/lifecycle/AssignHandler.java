package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class AssignHandler implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.ASSIGN;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.VERIFIED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command) {
        throw new UnsupportedOperationException("AssignHandler will be fully implemented in Phase 4");
    }
}