package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

@Component
public class AssignedState extends AbstractComplaintState {
    @Override
    public void startWork(Complaint complaint, User officer, String note) {
        complaint.setAssignedOfficer(officer);
        complaint.setStatus(ComplaintStatus.IN_PROGRESS);
    }

    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.ASSIGNED;
    }
}
