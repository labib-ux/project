package com.nagorikseba.shared.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Writes outbox rows (§7.3).
 *
 * <p>{@code MANDATORY} propagation is the whole safety argument: publishing must
 * join the caller's transaction, never start its own. If this ever ran in a
 * separate transaction, an event could commit while the state change that
 * justified it rolled back — exactly the dual-write bug the outbox pattern exists
 * to prevent. Calling this outside a transaction is a programming error and fails
 * loudly here rather than producing a phantom notification in production.
 */
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxMessage publish(String aggregateType, Long aggregateId, String eventType, String payload) {
        OutboxMessage message = OutboxMessage.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .status(OutboxMessage.STATUS_PENDING)
                .retryCount(0)
                .nextAttemptAt(clock.instant())
                .build();
        return outboxRepository.save(message);
    }
}
