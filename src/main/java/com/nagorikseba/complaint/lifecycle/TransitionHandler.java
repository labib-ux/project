package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;

import java.util.Set;

public interface TransitionHandler {

    ComplaintAction supportedAction();

    Set<ComplaintStatus> sourceStatuses();

    void execute(Complaint complaint, TransitionCommand command);
}