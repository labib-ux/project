package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.outbox.OutboxPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * ASSIGNED → IN_PROGRESS (§6, Phase 4).
 *
 * <p>Guard: the actor must be the assigned officer, or an ADMIN stepping in.
 * Anyone else — including an officer from the same department who was not
 * assigned — is refused with 403, so work ownership stays unambiguous. The
 * assignment row stays active through IN_PROGRESS; reassignment closes it.
 */
@Component
public class StartWorkHandler extends ComplaintMutator implements TransitionHandler {

    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public StartWorkHandler(UserRepository userRepository,
                            OutboxPublisher outboxPublisher,
                            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public ComplaintAction supportedAction() {
        return ComplaintAction.START;
    }

    @Override
    public Set<ComplaintStatus> sourceStatuses() {
        return Set.of(ComplaintStatus.ASSIGNED);
    }

    @Override
    public void execute(Complaint complaint, TransitionCommand command, Instant occurredAt) {
        User assigned = complaint.getAssignedOfficer();
        if (assigned == null || !assigned.getId().equals(command.actorId())) {
            User actor = userRepository.findById(command.actorId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Actor not found with id: " + command.actorId()));
            if (actor.getRole() != UserRole.ADMIN) {
                throw new AccessDeniedException("Only the assigned officer can start work on this complaint");
            }
        }
        changeStatus(complaint, ComplaintStatus.IN_PROGRESS);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("action", ComplaintAction.START.name());
        payload.put("actorId", command.actorId());
        if (command.note() == null) {
            payload.putNull("note");
        } else {
            payload.put("note", command.note());
        }
        outboxPublisher.publish(ComplaintLifecycleService.AGGREGATE_TYPE, complaint.getId(),
                "COMPLAINT_WORK_STARTED", write(payload));
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize start-work payload", e);
        }
    }
}
