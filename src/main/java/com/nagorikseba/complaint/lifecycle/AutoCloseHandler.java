package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
import com.nagorikseba.notification.ComplaintStatusChangedEvent;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * RESOLVED → CLOSED by the system (§6 AUTO_CLOSE, Phase 5 follow-up).
 *
 * <p>Fires when a complaint sits RESOLVED for {@link #GRACE_PERIOD} with no
 * citizen rating: the latest attempt flips to CLOSED with no rating attached,
 * so the citizen's silence reads as acceptance rather than a missing row.
 * Unlike {@link CloseHandler} this path works for anonymous complaints too —
 * there is no owner to rate, so waiting on one would strand them RESOLVED
 * forever. Only the scheduler (SYSTEM actor) may invoke it; the lifecycle
 * service refuses human actors by routing this action exclusively through
 * {@code executeAsSystem}.
 */
@Component
public class AutoCloseHandler extends ComplaintMutator implements TransitionHandler {

    /** Citizen silence past this long counts as acceptance. */
    public static final Duration GRACE_PERIOD = Duration.ofDays(7);

    private final ResolutionAttemptRepository attemptRepository;
    private final OutboxPublisher outboxPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AutoCloseHandler(ResolutionAttemptRepository attemptRepository,
                            OutboxPublisher outboxPublisher,
                            ApplicationEventPublisher eventPublisher,
                            ObjectMapper objectMapper) {
        this.attemptRepository = attemptRepository;
        this.outboxPublisher = outboxPublisher;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.AUTO_CLOSE;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.RESOLVED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        // Defense in depth: the scheduler already filters by age, but a
        // misconfigured manual trigger must not close a fresh resolution.
        if (complaint.getResolvedAt() == null
                || Duration.between(complaint.getResolvedAt(), occurredAt).compareTo(GRACE_PERIOD) < 0) {
            throw new IllegalStateException("Complaint " + complaint.getReferenceCode()
                    + " has not been RESOLVED for 7 days; refusing AUTO_CLOSE");
        }

        // Every RESOLVED complaint passed through ResolveHandler, which always
        // opens an attempt — a missing one is corrupt data, and backfilling it
        // here would require inventing a resolver (resolved_by is NOT NULL).
        ResolutionAttempt attempt = attemptRepository
                .findFirstByComplaintIdOrderByAttemptNumberDesc(complaint.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No resolution attempt for complaint " + complaint.getReferenceCode()));
        attempt.setOutcome(ResolutionAttempt.Outcome.CLOSED);
        attemptRepository.save(attempt);

        changeStatus(complaint, ComplaintStatus.CLOSED);
        markClosedAt(complaint, occurredAt);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("action", ComplaintAction.AUTO_CLOSE.name());
        payload.put("autoClosed", true);
        payload.putNull("actorId");
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "COMPLAINT_CLOSED", write(payload));
        eventPublisher.publishEvent(new ComplaintStatusChangedEvent(
                complaint.getId(), null, ComplaintStatus.RESOLVED.name(),
                ComplaintStatus.CLOSED.name(), "Auto-closed after 7 days without citizen rating",
                occurredAt));
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize auto-close payload", e);
        }
    }
}
