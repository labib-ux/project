package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintTransition;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.repo.ComplaintTransitionRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.exception.ConflictException;
import com.nagorikseba.shared.exception.InvalidStateTransitionException;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import com.nagorikseba.shared.time.Clock;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ComplaintLifecycleService {

    private final ComplaintRepository complaintRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;
    private final Map<ComplaintAction, TransitionHandler> handlers;

    public ComplaintLifecycleService(
            ComplaintRepository complaintRepository,
            ComplaintTransitionRepository transitionRepository,
            UserRepository userRepository,
            OutboxPublisher outboxPublisher,
            Clock clock,
            List<TransitionHandler> handlerList) {
        this.complaintRepository = complaintRepository;
        this.transitionRepository = transitionRepository;
        this.userRepository = userRepository;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(TransitionHandler::supportedAction, h -> h));
    }

    @Transactional
    public Complaint execute(TransitionCommand command) {
        Complaint complaint = complaintRepository.findAndLockById(command.complaintId())
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found with id: " + command.complaintId()));

        if (complaint.getVersion() != command.expectedVersion()) {
            throw new ConflictException("Complaint has been modified by another user. Please refresh and try again.");
        }

        TransitionHandler handler = handlers.get(command.action());
        if (handler == null || !handler.sourceStatuses().contains(complaint.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot perform action " + command.action() + " on complaint in status " + complaint.getStatus());
        }

        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new ResourceNotFoundException("Actor not found with id: " + command.actorId()));

        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            if (transitionRepository.existsByComplaintIdAndIdempotencyKey(command.complaintId(), command.idempotencyKey())) {
                throw new ConflictException("Duplicate request detected. This action has already been processed.");
            }
        }

        ComplaintStatus fromStatus = complaint.getStatus();
        Instant now = clock.instant();

        handler.execute(complaint, command);

        complaint.setLastTransitionAt(now);
        complaint.incrementVersion();

        ComplaintTransition transition = ComplaintTransition.builder()
                .complaint(complaint)
                .fromStatus(fromStatus)
                .toStatus(complaint.getStatus())
                .action(command.action())
                .actor(actor)
                .actorRole(actor.getRole().name())
                .note(command.note())
                .idempotencyKey(command.idempotencyKey())
                .build();
        complaint.addTransition(transition);
        transitionRepository.save(transition);

        publishOutboxEvent(complaint, fromStatus, command.action(), actor.getId(), command.note(), now);

        return complaint;
    }

    private void publishOutboxEvent(Complaint complaint, ComplaintStatus from, ComplaintAction action, Long actorId, String note, Instant occurredAt) {
        String eventType = "COMPLAINT_STATUS_CHANGED";
        String payload = """
                {
                    "complaintId": %d,
                    "referenceCode": "%s",
                    "from": "%s",
                    "to": "%s",
                    "actorId": %d,
                    "note": "%s",
                    "occurredAt": "%s"
                }
                """.formatted(
                complaint.getId(),
                complaint.getReferenceCode(),
                from,
                complaint.getStatus(),
                actorId,
                note != null ? note.replace("\"", "\\\"") : "",
                occurredAt.toString()
        );
        outboxPublisher.publish("COMPLAINT", complaint.getId(), eventType, payload);
    }
}