package com.nagorikseba.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.enums.NotificationChannel;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox row → channel delivery (§7.4, R5).
 *
 * <p>Routing: {@code SMS_SEND}/{@code EMAIL_SEND} rows go to the matching
 * {@link ChannelSender}; {@code COMPLAINT_*} and {@code SLA_ESCALATION} rows
 * produce in-app notification rows. In-app writes carry the outbox row id and
 * rely on the partial unique constraint {@code uq_notification_outbox_user}:
 * a redelivered row collides instead of duplicating, and the collision is
 * treated as delivered.
 */
@Service
@Slf4j
public class NotificationDispatcher {

    private final Map<String, ChannelSender> senders;
    private final NotificationMessageRepository notificationRepository;
    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final NotificationTemplateService templates;
    private final ObjectMapper objectMapper;

    public NotificationDispatcher(List<ChannelSender> senderBeans,
                                  NotificationMessageRepository notificationRepository,
                                  ComplaintRepository complaintRepository,
                                  UserRepository userRepository,
                                  NotificationTemplateService templates,
                                  ObjectMapper objectMapper) {
        Map<String, ChannelSender> byChannel = new LinkedHashMap<>();
        for (ChannelSender sender : senderBeans) {
            if (byChannel.put(sender.channel(), sender) != null) {
                throw new IllegalStateException(
                        "Two senders claim channel " + sender.channel());
            }
        }
        this.senders = Map.copyOf(byChannel);
        this.notificationRepository = notificationRepository;
        this.complaintRepository = complaintRepository;
        this.userRepository = userRepository;
        this.templates = templates;
        this.objectMapper = objectMapper;
    }

    /** Channels this dispatcher can deliver — asserted by tests. */
    public List<String> channels() {
        return List.copyOf(senders.keySet());
    }

    @Transactional
    public void dispatch(OutboxMessage message) throws Exception {
        switch (message.getEventType()) {
            case "SMS_SEND", "EMAIL_SEND" -> {
                String channel = message.getEventType().startsWith("SMS") ? "SMS" : "EMAIL";
                ChannelSender sender = senders.get(channel);
                if (sender == null) {
                    throw new IllegalStateException("No sender for channel " + channel);
                }
                sender.send(message);
            }
            case "SLA_ESCALATION" -> dispatchEscalation(message);
            default -> {
                if (message.getEventType().startsWith("COMPLAINT_")) {
                    dispatchComplaintEvent(message);
                } else {
                    log.warn("Unknown outbox event type {}; marking delivered to avoid poison retries",
                            message.getEventType());
                }
            }
        }
    }

    private void dispatchComplaintEvent(OutboxMessage message) {
        JsonNode payload = readPayload(message);
        Long complaintId = payload.path("complaintId").asLong();
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
        String templateCode = templateFor(message.getEventType());
        // Citizen always; assigned officer too when the payload names one.
        if (complaint.getCitizen() != null) {
            writeInApp(message, complaint.getCitizen().getId(), complaint, templateCode, payload);
        }
        if (payload.hasNonNull("officerId")) {
            writeInApp(message, payload.path("officerId").asLong(), complaint, templateCode, payload);
        }
    }

    private void dispatchEscalation(OutboxMessage message) {
        JsonNode payload = readPayload(message);
        if (!payload.hasNonNull("escalatedToUserId")) {
            log.info("SLA escalation {} has no recipient; audit row only", message.getId());
            return;
        }
        Long userId = payload.path("escalatedToUserId").asLong();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        String text = templates.render("SLA_ESCALATION", NotificationTemplateService.DEFAULT_LOCALE, Map.of(
                "referenceCode", payload.path("referenceCode").asText(""),
                "status", "SLA breached",
                "note", payload.path("explanation").asText("")));
        try {
            notificationRepository.save(NotificationMessage.builder()
                    .user(user)
                    .channel(NotificationChannel.IN_APP)
                    .templateCode("SLA_ESCALATION")
                    .title("SLA escalation")
                    .message(text)
                    .outbox(message)
                    .build());
        } catch (DataIntegrityViolationException alreadyDelivered) {
            log.debug("Redelivered outbox row {} converged on unique constraint", message.getId());
        }
    }

    private void writeInApp(OutboxMessage message, Long userId, Complaint complaint,
                            String templateCode, JsonNode payload) {
        if (notificationRepository.existsByOutboxIdAndUserId(message.getId(), userId)) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        String text = templates.render(templateCode, NotificationTemplateService.DEFAULT_LOCALE, Map.of(
                "referenceCode", complaint.getReferenceCode(),
                "status", complaint.getStatus().name(),
                "note", payload.path("note").asText("")));
        try {
            notificationRepository.save(NotificationMessage.builder()
                    .user(user)
                    .complaint(complaint)
                    .channel(NotificationChannel.IN_APP)
                    .templateCode(templateCode)
                    .title(templateCode)
                    .message(text)
                    .outbox(message)
                    .build());
        } catch (DataIntegrityViolationException alreadyDelivered) {
            log.debug("Redelivered outbox row {} converged on unique constraint", message.getId());
        }
    }

    private String templateFor(String eventType) {
        return templates.supports(eventType) ? eventType : "COMPLAINT_SUBMITTED";
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
