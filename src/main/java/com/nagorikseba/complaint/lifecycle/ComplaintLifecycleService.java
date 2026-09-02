package com.nagorikseba.complaint.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.ComplaintMutator;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single entry point for every complaint state change (§7.1).
 *
 * <h2>Why one funnel</h2>
 * <p>Each transition needs the same five guarantees — a row lock, an optimistic
 * version check, a legality check, an audit row and an outbox event. Implementing
 * those per call site is how one of them eventually gets forgotten, so they live
 * here once and handlers supply only the action-specific part.
 *
 * <h2>Registry</h2>
 * <p>The action→handler map is built from every {@link TransitionHandler} bean at
 * construction. Registering a new action is therefore just adding a
 * {@code @Component}; a duplicate {@code supportedAction()} fails startup rather
 * than silently shadowing an edge. Actions with no handler are not legal
 * transitions and are refused with 422 — that is the honest answer for
 * ASSIGN/START/RESOLVE/CLOSE/REOPEN until Phases 4 and 5 land them.
 */
@Service
@Slf4j
public class ComplaintLifecycleService extends ComplaintMutator {

    private final ComplaintRepository complaintRepository;
    private final ComplaintTransitionRepository transitionRepository;
    private final UserRepository userRepository;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<ComplaintAction, TransitionHandler> handlers;

    public static final String AGGREGATE_TYPE = "COMPLAINT";
    public static final String EVENT_STATUS_CHANGED = "COMPLAINT_STATUS_CHANGED";

    public ComplaintLifecycleService(
            ComplaintRepository complaintRepository,
            ComplaintTransitionRepository transitionRepository,
            UserRepository userRepository,
            OutboxPublisher outboxPublisher,
            Clock clock,
            ObjectMapper objectMapper,
            List<TransitionHandler> handlerBeans) {
        this.complaintRepository = complaintRepository;
        this.transitionRepository = transitionRepository;
        this.userRepository = userRepository;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.handlers = buildRegistry(handlerBeans);
    }

    private static Map<ComplaintAction, TransitionHandler> buildRegistry(List<TransitionHandler> beans) {
        Map<ComplaintAction, TransitionHandler> registry = new EnumMap<>(ComplaintAction.class);
        for (TransitionHandler handler : beans) {
            TransitionHandler existing = registry.put(handler.supportedAction(), handler);
            if (existing != null) {
                throw new IllegalStateException("Two handlers claim action " + handler.supportedAction()
                        + ": " + existing.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        log.info("Complaint lifecycle registry: {}", registry.keySet());
        return Map.copyOf(registry);
    }

    /**
     * Apply one transition atomically.
     *
     * @throws ResourceNotFoundException      no such complaint or actor (404)
     * @throws ConflictException              stale {@code expectedVersion} (409)
     * @throws InvalidStateTransitionException the action is not legal from the
     *                                        current status (422)
     */
    @Transactional
    public Complaint execute(TransitionCommand command) {
        // 1. Lock the row first: everything after this is serialized per complaint,
        //    which is what makes the version check below a real guard and not a race.
        Complaint complaint = complaintRepository.findAndLockById(command.complaintId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Complaint not found with id: " + command.complaintId()));

        // 2. Replay check before the version check, deliberately. A retrying client
        //    resends the version it originally saw, which is now stale — checking the
        //    version first would answer 409 to a replay instead of returning the
        //    original outcome, and R3 requires the latter.
        if (command.hasIdempotencyKey()) {
            Optional<ComplaintTransition> replay = transitionRepository
                    .findByComplaintIdAndIdempotencyKey(command.complaintId(), command.idempotencyKey());
            if (replay.isPresent()) {
                log.debug("Idempotent replay of {} on complaint {}", command.action(), complaint.getReferenceCode());
                return complaint;
            }
        }

        // 3. Optimistic version check — stops a stale tab overwriting a fresh decision.
        if (complaint.getVersion() != command.expectedVersion()) {
            throw new ConflictException("This complaint changed since you loaded it (expected version "
                    + command.expectedVersion() + ", current " + complaint.getVersion() + "). Reload and try again.");
        }

        // 4. Handler lookup and 5. source-status check. Both answer 422: the request
        //    was well formed, the transition just is not legal from here.
        TransitionHandler handler = handlers.get(command.action());
        if (handler == null) {
            throw new InvalidStateTransitionException(
                    "Action " + command.action() + " is not supported yet");
        }
        if (!handler.sourceStatuses().contains(complaint.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot " + command.action() + " a complaint in status " + complaint.getStatus());
        }

        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new ResourceNotFoundException("Actor not found with id: " + command.actorId()));

        ComplaintStatus fromStatus = complaint.getStatus();
        Instant now = clock.instant();

        // 6. The action-specific part.
        handler.execute(complaint, command, now);

        // Version is Hibernate's to manage: mutating the aggregate marks it dirty and
        // @Version increments on flush. Bumping it by hand here would double-count and
        // make every subsequent expectedVersion wrong.
        markLastTransitionAt(complaint, now);

        ComplaintTransition transition = ComplaintTransition.builder()
                .complaint(complaint)
                .fromStatus(fromStatus)
                .toStatus(complaint.getStatus())
                .action(command.action())
                .actor(actor)
                .actorRole(actor.getRole().name())
                .note(command.note())
                .idempotencyKey(command.idempotencyKey())
                .createdAt(now)
                .build();
        transitionRepository.save(transition);

        publishStatusChanged(complaint, fromStatus, command, actor.getId(), now);
        return complaint;
    }

    /**
     * Record the SUBMIT edge and its event for a brand-new complaint.
     *
     * <p>Submission is not a transition — there is no prior state to leave and no
     * version to check — so it does not go through {@link #execute}. It still needs
     * the same audit row and outbox event, which is what this provides. Called by
     * the submission template inside its transaction.
     */
    @Transactional
    public ComplaintTransition recordSubmission(Complaint complaint, User citizen, Instant occurredAt) {
        ComplaintTransition transition = ComplaintTransition.builder()
                .complaint(complaint)
                .fromStatus(null)
                .toStatus(ComplaintStatus.SUBMITTED)
                .action(ComplaintAction.SUBMIT)
                .actor(citizen)
                .actorRole(citizen != null ? citizen.getRole().name() : "SYSTEM")
                .note(citizen != null ? "Complaint submitted" : "Anonymous complaint submitted")
                .createdAt(occurredAt)
                .build();
        transitionRepository.save(transition);

        ObjectNode payload = basePayload(complaint, occurredAt);
        payload.put("action", ComplaintAction.SUBMIT.name());
        payload.putNull("from");
        payload.put("to", ComplaintStatus.SUBMITTED.name());
        if (citizen != null) {
            payload.put("actorId", citizen.getId());
        } else {
            payload.putNull("actorId");
        }
        payload.put("note", transition.getNote());
        outboxPublisher.publish(AGGREGATE_TYPE, complaint.getId(), EVENT_STATUS_CHANGED, write(payload));
        return transition;
    }

    private void publishStatusChanged(Complaint complaint, ComplaintStatus from,
                                      TransitionCommand command, Long actorId, Instant occurredAt) {
        ObjectNode payload = basePayload(complaint, occurredAt);
        payload.put("action", command.action().name());
        payload.put("from", from.name());
        payload.put("to", complaint.getStatus().name());
        payload.put("actorId", actorId);
        if (command.note() == null) {
            payload.putNull("note");
        } else {
            payload.put("note", command.note());
        }
        outboxPublisher.publish(AGGREGATE_TYPE, complaint.getId(), EVENT_STATUS_CHANGED, write(payload));
    }

    private ObjectNode basePayload(Complaint complaint, Instant occurredAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", occurredAt.toString());
        return payload;
    }

    /**
     * Serializes the payload. Jackson rather than string concatenation: a note
     * containing a quote or newline would otherwise produce invalid JSON in a jsonb
     * column, and the failure would surface in the Phase 5 relay rather than here.
     */
    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }
    }

    /** The actions this build can actually perform — used by the handoff smoke tests. */
    public java.util.Set<ComplaintAction> supportedActions() {
        return handlers.keySet();
    }
}
