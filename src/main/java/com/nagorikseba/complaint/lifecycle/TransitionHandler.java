package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;

import java.time.Instant;
import java.util.Set;

/**
 * One legal edge of the complaint state machine (§6, §7.1).
 *
 * <p>Adding a transition means adding a {@code @Component} that implements this
 * interface and extends {@code ComplaintMutator} — nothing else. The registry in
 * {@link ComplaintLifecycleService} is built from every such bean at startup, so
 * there is no switch statement or enum map to remember to update. Two handlers
 * claiming the same {@link #supportedAction()} is a startup failure, not a
 * silently-lost transition.
 *
 * <p>Implementations are stateless singletons: all per-request state arrives in
 * the arguments. They validate action-specific preconditions and mutate the
 * aggregate; they do <em>not</em> lock rows, check versions, write audit rows or
 * publish events. The service does all of that around them, uniformly.
 */
public interface TransitionHandler {

    /** The action this handler owns. Must be unique across all handlers. */
    ComplaintAction supportedAction();

    /**
     * Statuses this action may be applied from. The service rejects anything else
     * with 422 before the handler is ever called, so implementations can assume the
     * complaint is in one of these.
     */
    Set<ComplaintStatus> sourceStatuses();

    /**
     * Apply the transition.
     *
     * @param complaint   the locked aggregate, already version-checked
     * @param command     the request, already validated for source status
     * @param occurredAt  the single instant for this transition, from the
     *                    {@code Clock} bean — handlers must not read the wall clock,
     *                    or the audit row, the aggregate timestamps and the outbox
     *                    payload end up disagreeing by microseconds
     */
    void execute(Complaint complaint, TransitionCommand command, Instant occurredAt);
}
