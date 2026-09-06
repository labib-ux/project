package com.nagorikseba.notification;

import com.nagorikseba.shared.outbox.OutboxMessage;

/**
 * One delivery channel (§7.4, N7/N8).
 *
 * <p>Throwing signals failure: the worker records the error, backs off and
 * retries. Provider metadata should carry the outbox row id so a provider-side
 * retry stays idempotent (R5).
 */
public interface ChannelSender {

    /** SMS, EMAIL or IN_APP — must be unique across senders. */
    String channel();

    void send(OutboxMessage message) throws Exception;
}
