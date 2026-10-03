package com.nagorikseba.notification.api.dto;

import com.nagorikseba.enums.NotificationChannel;
import com.nagorikseba.notification.NotificationMessage;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * In-app notification projection for {@code /api/notifications}.
 *
 * <p>Carries the reference code rather than the complaint id so a client can link
 * to {@code /authority/complaints/{referenceCode}} without a second lookup, and
 * never exposes the {@code outboxId} — that is internal relay bookkeeping and
 * leaking it would invite clients to reason about delivery internals.
 */
public record NotificationResponse(
        Long id,
        String templateCode,
        String title,
        String message,
        NotificationChannel channel,
        String referenceCode,
        String complaintStatus,
        boolean read,
        Instant readAt,
        LocalDateTime sentAt
) {

    /**
     * Null-safe mapping: {@code complaint} is LAZY and is absent for notifications
     * that are not tied to a complaint (SLA escalations may be, but a notification
     * written before the complaint was visible cannot be), so both the reference
     * code and the status are read defensively rather than dereferenced.
     */
    public static NotificationResponse from(NotificationMessage notification) {
        String referenceCode = null;
        String status = null;
        if (notification.getComplaint() != null) {
            referenceCode = notification.getComplaint().getReferenceCode();
            status = notification.getComplaint().getStatus() != null
                    ? notification.getComplaint().getStatus().name() : null;
        }
        return new NotificationResponse(
                notification.getId(),
                notification.getTemplateCode(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getChannel(),
                referenceCode,
                status,
                notification.isRead(),
                notification.getReadAt(),
                notification.getSentAt());
    }
}