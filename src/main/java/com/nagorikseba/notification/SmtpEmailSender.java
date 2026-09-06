package com.nagorikseba.notification;

import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production email sender behind {@code JavaMailSender} (N8).
 *
 * <p>Wiring point: configure SMTP host/credentials from the environment and
 * send a MIME message with the outbox row id in a header for idempotent
 * retries (R5). Until then it logs like the dev sender.
 */
@Component
@Profile("prod")
@Slf4j
public class SmtpEmailSender implements ChannelSender {

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public void send(OutboxMessage message) {
        log.info("PROD EMAIL outbox_id={} payload={} (SMTP wiring point)",
                message.getId(), message.getPayload());
    }
}
