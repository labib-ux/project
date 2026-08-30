package com.nagorikseba.state;

import com.nagorikseba.enums.ComplaintStatus;
import org.springframework.stereotype.Component;

@Component
public class ClosedState extends AbstractComplaintState {
    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.CLOSED;
    }
}
