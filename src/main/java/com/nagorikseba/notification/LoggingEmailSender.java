package com.nagorikseba.notification;

import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev email sender (N8): logs instead of opening an SMTP session.
 */
@Component
@Profile("!prod")
@Slf4j
public class LoggingEmailSender implements ChannelSender {

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public void send(OutboxMessage message) {
        log.info("DEV EMAIL outbox_id={} payload={}", message.getId(), message.getPayload());
    }
}
