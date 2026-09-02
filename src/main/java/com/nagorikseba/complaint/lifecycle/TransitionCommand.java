package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.enums.ComplaintAction;

import java.util.List;

/**
 * One request to move a complaint through the state machine (§7.1).
 *
 * @param action                what to do
 * @param complaintId           the aggregate to act on
 * @param actorId               the authenticated user performing the action; recorded
 *                              on the audit row and checked for ownership by CANCEL
 * @param note                  free text — mandatory for REJECT and CANCEL, optional
 *                              elsewhere
 * @param evidenceAttachmentIds work-proof photos supplied with the action; unused
 *                              until RESOLVE lands in Phase 5, present so the record
 *                              shape does not change under Phase 4/5 callers
 * @param idempotencyKey        client-generated replay guard, unique per complaint.
 *                              Replaying a key returns the original outcome instead
 *                              of applying the action twice
 * @param expectedVersion       the aggregate version the caller last saw. A mismatch
 *                              is a 409, which is how a stale dashboard tab is stopped
 *                              from overwriting a decision someone else already made
 */
public record TransitionCommand(
        ComplaintAction action,
        Long complaintId,
        Long actorId,
        String note,
        List<Long> evidenceAttachmentIds,
        String idempotencyKey,
        int expectedVersion
) {

    /** The common case: an action with a note and no attached evidence. */
    public static TransitionCommand of(ComplaintAction action, Long complaintId, Long actorId,
                                       String note, String idempotencyKey, int expectedVersion) {
        return new TransitionCommand(action, complaintId, actorId, note, List.of(), idempotencyKey, expectedVersion);
    }

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
