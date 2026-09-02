package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class InProgressState extends AbstractComplaintState {
    @Override
    public void resolve(Complaint complaint, User officer, String note) {
        complaint.setResolvedAt(LocalDateTime.now());
        complaint.setStatus(ComplaintStatus.RESOLVED);
    }

    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.IN_PROGRESS;
    }
}
