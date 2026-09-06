package com.nagorikseba.notification;

import java.time.Instant;

/**
 * One complaint transition, IDs only (§7.3).
 *
 * <p>Published inside the lifecycle transaction by the Phase 5 handlers;
 * consumed after commit by {@code NotificationListener}. Carrying IDs — never
 * JPA entities — avoids lazy-loading accidents once the transaction closes.
 */
public record ComplaintStatusChangedEvent(
        Long complaintId,
        Long actorId,
        String from,
        String to,
        String note,
        Instant occurredAt
) {
}
