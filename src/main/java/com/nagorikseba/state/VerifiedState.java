package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

@Component
public class VerifiedState extends AbstractComplaintState {
    @Override
    public void assign(Complaint complaint, Department dept, User officer, String note) {
        complaint.setAssignedDepartment(dept);
        complaint.setStatus(ComplaintStatus.ASSIGNED);
    }

    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.VERIFIED;
    }
}
