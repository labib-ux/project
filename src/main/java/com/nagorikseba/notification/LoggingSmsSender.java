package com.nagorikseba.notification;

import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev SMS sender (N7): logs instead of calling Twilio.
 *
 * <p>Active on every profile except {@code prod}. The {@code prod} replacement
 * ({@code TwilioSmsSender}) carries the same channel name, so dispatch code
 * never branches on environment.
 */
@Component
@Profile("!prod")
@Slf4j
public class LoggingSmsSender implements ChannelSender {

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    public void send(OutboxMessage message) {
        log.info("DEV SMS outbox_id={} payload={}", message.getId(), message.getPayload());
    }
}
