package com.nagorikseba.notification;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Post-commit fan-out for status changes (N9/N10, §7.3).
 *
 * <p>Runs {@code AFTER_COMMIT}: the transition is durable before any channel
 * is touched, and in-app rows are written by the outbox worker (deduplicated
 * by outbox id) — so this listener only ensures durable {@code SMS_SEND} /
 * {@code EMAIL_SEND} outbox rows exist for recipients with a phone/email.
 * Anonymous reports without an account still reach their contact phone when
 * one was supplied. Each save runs in its own transaction (there is none left
 * after commit), and every instant comes from the {@code Clock} bean.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationListener {

    private final ComplaintRepository complaintRepository;
    private final OutboxRepository outboxRepository;
    private final NotificationTemplateService templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onStatusChanged(ComplaintStatusChangedEvent event) {
        Complaint complaint = complaintRepository.findById(event.complaintId()).orElse(null);
        if (complaint == null) {
            log.warn("Skipping notification for unknown complaint {}", event.complaintId());
            return;
        }
        String templateCode = templateFor(event.to());
        Map<String, String> variables = Map.of(
                "referenceCode", complaint.getReferenceCode(),
                "status", event.to(),
                "note", event.note() != null ? event.note() : "");

        User citizen = complaint.getCitizen();
        if (citizen != null) {
            if (citizen.getPhone() != null && !citizen.getPhone().isBlank()) {
                saveChannelRow(complaint, citizen.getPhone(), null, templateCode, variables);
            }
            if (citizen.getEmail() != null && !citizen.getEmail().isBlank()) {
                saveChannelRow(complaint, null, citizen.getEmail(), templateCode, variables);
            }
        } else if (complaint.getAnonymousContactPhone() != null
                && !complaint.getAnonymousContactPhone().isBlank()) {
            saveChannelRow(complaint, complaint.getAnonymousContactPhone(), null, templateCode, variables);
        }
    }

    private String templateFor(String toStatus) {
        String code = "COMPLAINT_" + toStatus;
        return templates.supports(code) ? code : "COMPLAINT_SUBMITTED";
    }

    private void saveChannelRow(Complaint complaint, String phone, String email,
                                String templateCode, Map<String, String> variables) {
        boolean sms = phone != null;
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("to", sms ? phone : email);
        payload.put("locale", NotificationTemplateService.DEFAULT_LOCALE);
        payload.put("text", templates.render(templateCode,
                NotificationTemplateService.DEFAULT_LOCALE, variables));
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("complaintId", String.valueOf(complaint.getId()));
        outboxRepository.save(OutboxMessage.builder()
                .aggregateType("COMPLAINT")
                .aggregateId(complaint.getId())
                .eventType(sms ? "SMS_SEND" : "EMAIL_SEND")
                .payload(write(payload))
                .status(OutboxMessage.STATUS_PENDING)
                .retryCount(0)
                .nextAttemptAt(clock.instant())
                .build());
    }

    private String write(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize notification payload", e);
        }
    }

}
