package com.nagorikseba.complaint.domain;

import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;

import java.time.Instant;

/**
 * The capability that lets lifecycle code mutate a {@link Complaint} (§7.1).
 *
 * <p>{@code Complaint}'s status and lifecycle-timestamp setters are package-private,
 * so no class outside {@code complaint.domain} can call them directly. This base
 * class lives in that package and re-exposes them as {@code protected} — which
 * means the only way to reach them is to <em>extend this class</em>. Every
 * {@code TransitionHandler} does; nothing else in the codebase does.
 *
 * <p>The practical effect: a controller, a seeder, or a future service that tries
 * {@code complaint.setStatus(VERIFIED)} fails to compile. Status changes have to
 * go through {@code ComplaintLifecycleService.execute()}, which is where the row
 * lock, the version check, the audit row and the outbox write live. Bypassing any
 * one of those is how state machines rot, so the compiler holds the line.
 *
 * <p>Exactly two kinds of class extend this: the transition handlers, and the
 * lifecycle service itself (which stamps {@code lastTransitionAt} uniformly after
 * a handler runs). Both live in {@code complaint.lifecycle}.
 *
 * <p>Methods are {@code final} on purpose: a handler subclass overriding
 * {@code changeStatus} to do something else would defeat the point.
 */
public abstract class ComplaintMutator {

    protected final void changeStatus(Complaint complaint, ComplaintStatus newStatus) {
        complaint.setStatus(newStatus);
    }

    protected final void changePriority(Complaint complaint, Priority priority) {
        complaint.setPriority(priority);
    }

    protected final void markFirstVerifiedAt(Complaint complaint, Instant at) {
        if (complaint.getFirstVerifiedAt() == null) {
            complaint.setFirstVerifiedAt(at);
        }
    }

    protected final void markFirstAssignedAt(Complaint complaint, Instant at) {
        if (complaint.getFirstAssignedAt() == null) {
            complaint.setFirstAssignedAt(at);
        }
    }

    protected final void markResolvedAt(Complaint complaint, Instant at) {
        complaint.setResolvedAt(at);
    }

    protected final void markClosedAt(Complaint complaint, Instant at) {
        complaint.setClosedAt(at);
    }

    protected final void recordRejection(Complaint complaint, String reason) {
        complaint.setRejectionReason(reason);
    }

    protected final void recordCancellation(Complaint complaint, String reason) {
        complaint.setCancellationReason(reason);
    }

    protected final void changePublicVisibility(Complaint complaint, boolean visible) {
        complaint.setPublicVisible(visible);
    }

    protected final void changeModerationStatus(Complaint complaint, ModerationStatus status) {
        complaint.setModerationStatus(status);
    }

    protected final void assignTo(Complaint complaint, Department department, User officer) {
        complaint.setAssignedDepartment(department);
        complaint.setAssignedOfficer(officer);
    }

    protected final void incrementReopenCount(Complaint complaint) {
        complaint.incrementReopenCount();
    }

    /**
     * Stamped by the lifecycle service after a handler runs, so every transition
     * updates it identically rather than each handler remembering to.
     */
    protected final void markLastTransitionAt(Complaint complaint, Instant at) {
        complaint.setLastTransitionAt(at);
    }
}
