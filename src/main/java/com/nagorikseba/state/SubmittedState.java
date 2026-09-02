package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

@Component
public class SubmittedState extends AbstractComplaintState {
    @Override
    public void verify(Complaint complaint, User officer, String note) {
        complaint.setStatus(ComplaintStatus.VERIFIED);
    }

    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.SUBMITTED;
    }
}
