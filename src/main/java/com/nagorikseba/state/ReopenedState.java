package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.User;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

@Component
public class ReopenedState extends AbstractComplaintState {
    @Override
    public void assign(Complaint complaint, Department dept, User officer, String note) {
        complaint.setAssignedDepartment(dept);
        complaint.setStatus(ComplaintStatus.ASSIGNED);
    }

    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.REOPENED;
    }
}
