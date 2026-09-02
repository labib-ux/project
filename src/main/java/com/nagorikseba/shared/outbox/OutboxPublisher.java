package com.nagorikseba.shared.outbox;

import com.nagorikseba.shared.exception.FileStorageException;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;

    public OutboxPublisher(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void publish(String aggregateType, Long aggregateId, String eventType, String payload) {
        OutboxMessage message = new OutboxMessage();
        message.setAggregateType(aggregateType);
        message.setAggregateId(aggregateId);
        message.setEventType(eventType);
        message.setPayload(payload);
        message.setStatus("PENDING");
        outboxRepository.save(message);
    }
}