package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * SUBMITTED | VERIFIED → CANCELLED. The complainant withdraws their own report (§6).
 *
 * <p>Ownership is enforced here, not only in the controller, because the lifecycle
 * service is the single entry point for state changes and a future admin tool or
 * batch job could reach this handler without passing through a web layer. The
 * failure is {@link AccessDeniedException} rather than an argument exception so it
 * surfaces as 403 — "not yours to cancel" is an authorization answer, and a 400
 * would wrongly suggest the request was malformed.
 *
 * <p>Anonymous complaints have no owner and therefore cannot be cancelled at all;
 * the authority path for those is REJECT.
 */
@Component
public class CancelHandler extends ComplaintMutator implements TransitionHandler {

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.CANCEL;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.SUBMITTED, ComplaintStatus.VERIFIED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        if (command.note() == null || command.note().isBlank()) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        if (complaint.isAnonymous()) {
            throw new AccessDeniedException("Anonymous complaints cannot be cancelled");
        }
        if (!complaint.getCitizen().getId().equals(command.actorId())) {
            throw new AccessDeniedException("Only the complainant can cancel this complaint");
        }
        changeStatus(complaint, ComplaintStatus.CANCELLED);
        recordCancellation(complaint, command.note().trim());
        changePublicVisibility(complaint, false);
    }
}
