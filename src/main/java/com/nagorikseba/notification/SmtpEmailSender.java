package com.nagorikseba.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Production email sender behind {@code JavaMailSender} (N8).
 *
 * <p>Actually delivers when {@code app.notifications.email-enabled=true} (SMTP
 * host/credentials come from the standard {@code spring.mail.*} properties);
 * otherwise logs and returns, so prod deploys without mail credentials behave
 * exactly like the dev sender instead of failing every outbox row into FAILED.
 * Delivery failures throw, which is the worker's retry signal.
 */
@Component
@Profile("prod")
@Slf4j
public class SmtpEmailSender implements ChannelSender {

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender,
                           ObjectMapper objectMapper,
                           @Value("${app.notifications.email-enabled:false}") boolean enabled,
                           @Value("${app.notifications.mail-from:nagorik-seba@example.com}") String from) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.from = from;
    }

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public void send(OutboxMessage message) {
        JsonNode payload = readPayload(message);
        String to = payload.path("to").asText(null);
        if (!enabled) {
            log.info("PROD EMAIL (disabled, logged) outbox_id={} to={} payload={}",
                    message.getId(), to, message.getPayload());
            return;
        }
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("EMAIL_SEND row " + message.getId() + " has no recipient");
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(to);
        mail.setSubject("[Nagorik Seba] Complaint "
                + payload.path("referenceCode").asText("update"));
        mail.setText(payload.path("text").asText(""));
        mailSender.send(mail);
        log.info("PROD EMAIL sent outbox_id={} to={}", message.getId(), to);
    }

    private JsonNode readPayload(OutboxMessage message) {
        try {
            return objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Outbox row " + message.getId() + " carries invalid JSON", e);
        }
    }
}
