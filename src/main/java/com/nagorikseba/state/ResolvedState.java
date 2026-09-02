package com.nagorikseba.state;

import com.nagorikseba.entity.Complaint;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.enums.ComplaintStatus;
import com.nagorikseba.enums.Priority;
import com.nagorikseba.service.SlaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ResolvedState extends AbstractComplaintState {

    private final SlaService slaService;

    @Override
    public void close(Complaint complaint, User citizen, int rating, String feedback) {
        complaint.setRating(rating);
        complaint.setRatingFeedback(feedback);
        complaint.setStatus(ComplaintStatus.CLOSED);
    }

    @Override
    public void reopen(Complaint complaint, User citizen, String reason) {
        complaint.setStatus(ComplaintStatus.REOPENED);
        complaint.setReopenReason(reason);
        complaint.setReopenCount(complaint.getReopenCount() + 1);
        complaint.setPriority(Priority.HIGH); // Escalate upon reopen
        
        // SLA Reset properly as requested
        LocalDateTime newDeadline = slaService.calculateDeadline(complaint.getCategory(), Priority.HIGH);
        complaint.setDeadlineAt(newDeadline);
        
        // Clear resolution time since it's open again
        complaint.setResolvedAt(null);
    }

    @Override
    public ComplaintStatus getStatusName() {
        return ComplaintStatus.RESOLVED;
    }
}
