package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.notification.ComplaintStatusChangedEvent;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * RESOLVED → CLOSED (§6, Phase 5).
 *
 * <p>Citizen-only: only the owning citizen may rate their own resolution, so
 * anonymous complaints (no owner) can never close this way. Rating 1–5 is
 * required (R10: the unique attempt row makes double-rating a constraint
 * outcome, not a second row). The latest attempt flips to CLOSED with the
 * rating attached; a complaint resolved before attempts existed gets one
 * backfilled so history stays complete.
 */
@Component
public class CloseHandler extends ComplaintMutator implements TransitionHandler {

    private final UserRepository userRepository;
    private final ResolutionAttemptRepository attemptRepository;
    private final OutboxPublisher outboxPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public CloseHandler(UserRepository userRepository,
                        ResolutionAttemptRepository attemptRepository,
                        OutboxPublisher outboxPublisher,
                        ApplicationEventPublisher eventPublisher,
                        ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.attemptRepository = attemptRepository;
        this.outboxPublisher = outboxPublisher;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.CLOSE;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.RESOLVED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        if (complaint.isAnonymous()
                || complaint.getCitizen() == null
                || !complaint.getCitizen().getId().equals(command.actorId())) {
            throw new AccessDeniedException("Only the complainant can close this complaint");
        }
        if (command.rating() == null || command.rating() < 1 || command.rating() > 5) {
            throw new IllegalArgumentException("A rating from 1 to 5 is required to close a complaint");
        }
        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Actor not found with id: " + command.actorId()));

        ResolutionAttempt attempt = attemptRepository
                .findFirstByComplaintIdOrderByAttemptNumberDesc(complaint.getId())
                .orElseGet(() -> attemptRepository.save(ResolutionAttempt.builder()
                        .complaint(complaint)
                        .attemptNumber(1)
                        .resolvedAt(complaint.getResolvedAt() != null
                                ? complaint.getResolvedAt() : occurredAt)
                        .resolvedBy(actor)
                        .outcome(ResolutionAttempt.Outcome.PENDING_CITIZEN)
                        .build()));
        attempt.setOutcome(ResolutionAttempt.Outcome.CLOSED);
        attempt.setRating(command.rating());
        attempt.setRatingFeedback(command.feedback() != null && !command.feedback().isBlank()
                ? command.feedback().trim() : null);
        attempt.setRatedAt(occurredAt);
        attemptRepository.save(attempt);

        changeStatus(complaint, ComplaintStatus.CLOSED);
        markClosedAt(complaint, occurredAt);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("action", ComplaintAction.CLOSE.name());
        payload.put("rating", command.rating());
        payload.put("actorId", actor.getId());
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "COMPLAINT_CLOSED", write(payload));
        eventPublisher.publishEvent(new ComplaintStatusChangedEvent(
                complaint.getId(), actor.getId(), ComplaintStatus.RESOLVED.name(),
                ComplaintStatus.CLOSED.name(), command.note(), occurredAt));
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize close payload", e);
        }
    }
}
