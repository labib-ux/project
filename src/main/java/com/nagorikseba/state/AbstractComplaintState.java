package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.entity.Department;
import com.nagorikseba.entity.User;
import com.nagorikseba.exception.ConflictException;

public abstract class AbstractComplaintState implements ComplaintState {
    @Override
    public void verify(Complaint complaint, User officer, String note) {
        throw new ConflictException("Cannot transition to VERIFIED from " + getStatusName());
    }

    @Override
    public void assign(Complaint complaint, Department dept, User officer, String note) {
        throw new ConflictException("Cannot transition to ASSIGNED from " + getStatusName());
    }

    @Override
    public void startWork(Complaint complaint, User officer, String note) {
        throw new ConflictException("Cannot transition to IN_PROGRESS from " + getStatusName());
    }

    @Override
    public void resolve(Complaint complaint, User officer, String note) {
        throw new ConflictException("Cannot transition to RESOLVED from " + getStatusName());
    }

    @Override
    public void close(Complaint complaint, User citizen, int rating, String feedback) {
        throw new ConflictException("Cannot transition to CLOSED from " + getStatusName());
    }

    @Override
    public void reopen(Complaint complaint, User citizen, String reason) {
        throw new ConflictException("Cannot transition to REOPENED from " + getStatusName());
    }
}
