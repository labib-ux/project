package com.nagorikseba.notification;

import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production SMS sender behind the Twilio API (N7).
 *
 * <p>Wiring point: drop the Twilio SDK on the classpath, read
 * {@code TWILIO_SID}/{@code TWILIO_TOKEN}/{@code TWILIO_FROM} from the
 * environment, and replace the log line with the API call — keeping the
 * outbox row id in provider metadata for idempotent retries (R5). Until then
 * it logs like the dev sender so every profile delivers without loss.
 */
@Component
@Profile("prod")
@Slf4j
public class TwilioSmsSender implements ChannelSender {

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    public void send(OutboxMessage message) {
        log.info("PROD SMS outbox_id={} payload={} (Twilio wiring point)",
                message.getId(), message.getPayload());
    }
}
