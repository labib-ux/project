package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.ResolutionAttempt;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.AttachmentRepository;
import com.nagorikseba.complaint.repo.ResolutionAttemptRepository;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.notification.ComplaintStatusChangedEvent;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import com.nagorikseba.sla.SlaBreachScanner;
import com.nagorikseba.sla.SlaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * IN_PROGRESS → RESOLVED (§6, Phase 5).
 *
 * <p>Guards: the actor is the assigned officer (or an admin override), and at
 * least one evidence attachment rides with the action — each must exist,
 * belong to this complaint and be un-deleted. Evidence linkage to the audit
 * row rides on {@link #transitionMetadata}: the service stores it on the
 * RESOLVE transition, and {@code AttachmentService} links the rows in a
 * BEFORE_COMMIT listener in the same transaction. Opens a
 * {@code resolution_attempts} row in PENDING_CITIZEN, ensures the SLA
 * instance, clears the active SLA breach, and publishes both the status event
 * (for channel fan-out) and a {@code COMPLAINT_RESOLVED} outbox row.
 */
@Component
public class ResolveHandler extends ComplaintMutator implements TransitionHandler {

    private final UserRepository userRepository;
    private final AttachmentRepository attachmentRepository;
    private final ResolutionAttemptRepository attemptRepository;
    private final SlaBreachScanner breachScanner;
    private final SlaService slaService;
    private final OutboxPublisher outboxPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ResolveHandler(UserRepository userRepository,
                          AttachmentRepository attachmentRepository,
                          ResolutionAttemptRepository attemptRepository,
                          SlaBreachScanner breachScanner,
                          SlaService slaService,
                          OutboxPublisher outboxPublisher,
                          ApplicationEventPublisher eventPublisher,
                          ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.attachmentRepository = attachmentRepository;
        this.attemptRepository = attemptRepository;
        this.breachScanner = breachScanner;
        this.slaService = slaService;
        this.outboxPublisher = outboxPublisher;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.RESOLVE;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.IN_PROGRESS);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        User assigned = complaint.getAssignedOfficer();
        if (assigned == null || !assigned.getId().equals(command.actorId())) {
            User actor = userRepository.findById(command.actorId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Actor not found with id: " + command.actorId()));
            if (actor.getRole() != UserRole.ADMIN) {
                throw new AccessDeniedException("Only the assigned officer can resolve this complaint");
            }
        }
        List<Long> evidenceIds = command.evidenceAttachmentIds();
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one work-proof photo is required to resolve a complaint");
        }
        for (Long attachmentId : evidenceIds) {
            Attachment attachment = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Attachment not found: " + attachmentId));
            if (!attachment.getComplaint().getId().equals(complaint.getId()) || attachment.isDeleted()) {
                throw new IllegalArgumentException(
                        "Attachment " + attachmentId + " is not usable proof for this complaint");
            }
        }

        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Actor not found with id: " + command.actorId()));
        int attemptNumber = (int) attemptRepository.countByComplaintId(complaint.getId()) + 1;
        attemptRepository.save(ResolutionAttempt.builder()
                .complaint(complaint)
                .attemptNumber(attemptNumber)
                .resolvedAt(occurredAt)
                .resolvedBy(actor)
                .resolutionNote(command.note() != null ? command.note().trim() : null)
                .outcome(ResolutionAttempt.Outcome.PENDING_CITIZEN)
                .build());

        changeStatus(complaint, ComplaintStatus.RESOLVED);
        markResolvedAt(complaint, occurredAt);
        slaService.ensureInstance(complaint);
        breachScanner.clearBreachOnResolve(complaint.getId(), occurredAt);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("action", ComplaintAction.RESOLVE.name());
        payload.put("attemptNumber", attemptNumber);
        payload.put("evidenceCount", evidenceIds.size());
        payload.put("actorId", actor.getId());
        if (command.note() == null) {
            payload.putNull("note");
        } else {
            payload.put("note", command.note());
        }
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "COMPLAINT_RESOLVED", write(payload));
        eventPublisher.publishEvent(new ComplaintStatusChangedEvent(
                complaint.getId(), actor.getId(), ComplaintStatus.IN_PROGRESS.name(),
                ComplaintStatus.RESOLVED.name(), command.note(), occurredAt));
    }

    @Override
    public String transitionMetadata(Complaint complaint, TransitionCommand command) {
        // Read back in the same transaction: the attempt row execute() just
        // saved (handlers stay stateless — no per-request fields to race on).
        int attemptNumber = attemptRepository
                .findFirstByComplaintIdOrderByAttemptNumberDesc(complaint.getId())
                .map(ResolutionAttempt::getAttemptNumber).orElse(0);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("attemptNumber", attemptNumber);
        var evidence = metadata.putArray("evidenceIds");
        if (command.evidenceAttachmentIds() != null) {
            command.evidenceAttachmentIds().forEach(evidence::add);
        }
        return write(metadata);
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize resolve payload", e);
        }
    }
}
