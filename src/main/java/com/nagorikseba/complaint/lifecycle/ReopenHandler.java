package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
import com.nagorikseba.notification.ComplaintStatusChangedEvent;
import com.nagorikseba.shared.exception.InvalidStateTransitionException;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import com.nagorikseba.sla.SlaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * RESOLVED → REOPENED (§6, Phase 5).
 *
 * <p>Citizen-only with a mandatory reason. Five reopens is the terminal
 * budget: at {@code reopenCount == 5} the complaint is refused with a clear
 * 422 (the authority path from there is REJECT). A reopen escalates by
 * construction — priority jumps to HIGH, the SLA is recalculated at half
 * hours, and a councilor escalation outbox row goes out alongside the
 * standard one.
 */
@Component
public class ReopenHandler extends ComplaintMutator implements TransitionHandler {

    /** Reopen budget: the 6th reopen attempt is refused. */
    public static final int MAX_REOPENS = 5;

    private final ResolutionAttemptRepository attemptRepository;
    private final SlaService slaService;
    private final OutboxPublisher outboxPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ReopenHandler(ResolutionAttemptRepository attemptRepository,
                         SlaService slaService,
                         OutboxPublisher outboxPublisher,
                         ApplicationEventPublisher eventPublisher,
                         ObjectMapper objectMapper) {
        this.attemptRepository = attemptRepository;
        this.slaService = slaService;
        this.outboxPublisher = outboxPublisher;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.REOPEN;
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
            throw new AccessDeniedException("Only the complainant can reopen this complaint");
        }
        if (command.note() == null || command.note().isBlank()) {
            throw new IllegalArgumentException("A reopen reason is required");
        }
        if (complaint.getReopenCount() >= MAX_REOPENS) {
            throw new InvalidStateTransitionException(
                    "This complaint has already been reopened 5 times and cannot be reopened again");
        }

        ResolutionAttempt attempt = attemptRepository
                .findFirstByComplaintIdOrderByAttemptNumberDesc(complaint.getId())
                .orElse(null);
        if (attempt != null) {
            attempt.setOutcome(ResolutionAttempt.Outcome.REOPENED);
            attempt.setReopenReason(command.note().trim());
            attempt.setReopenedAt(occurredAt);
            attemptRepository.save(attempt);
        }

        incrementReopenCount(complaint);
        changePriority(complaint, Priority.HIGH);
        changeStatus(complaint, ComplaintStatus.REOPENED);
        slaService.recalculateForReopen(complaint);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("action", ComplaintAction.REOPEN.name());
        payload.put("reopenCount", complaint.getReopenCount());
        payload.put("actorId", command.actorId());
        payload.put("note", command.note().trim());
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "COMPLAINT_REOPENED", write(payload));

        ObjectNode escalation = objectMapper.createObjectNode();
        escalation.put("complaintId", complaint.getId());
        escalation.put("referenceCode", complaint.getReferenceCode());
        escalation.put("occurredAt", occurredAt.toString());
        escalation.put("escalationLevel", 1);
        escalation.put("explanation", "citizen reopened complaint ("
                + complaint.getReopenCount() + "/5); councilor review requested");
        escalation.put("note", command.note().trim());
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "SLA_ESCALATION", write(escalation));

        eventPublisher.publishEvent(new ComplaintStatusChangedEvent(
                complaint.getId(), command.actorId(), ComplaintStatus.RESOLVED.name(),
                ComplaintStatus.REOPENED.name(), command.note(), occurredAt));
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize reopen payload", e);
        }
    }
}
