package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
import com.nagorikseba.enums.ComplaintStatus;

public interface ComplaintState {
    void verify(Complaint complaint, User officer, String note);
    void assign(Complaint complaint, Department dept, User officer, String note);
    void startWork(Complaint complaint, User officer, String note);
    void resolve(Complaint complaint, User officer, String note);
    void close(Complaint complaint, User citizen, int rating, String feedback);
    void reopen(Complaint complaint, User citizen, String reason);
    ComplaintStatus getStatusName();
}
