package com.nagorikseba.sla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.notification.NotificationMessage;
import com.nagorikseba.notification.NotificationMessageRepository;
import com.nagorikseba.notification.NotificationTemplateService;
import com.nagorikseba.enums.NotificationChannel;
import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hourly SLA breach detection (L8, §3.4, R4).
 *
 * <p>Overdue instances (deadline past, never breached, complaint still active)
 * are claimed with {@code FOR UPDATE SKIP LOCKED}, so two app instances scan
 * disjoint sets; the partial unique index makes breach-once hold even if a
 * claim ever overlaps. Level 1 escalates to the ward councilor, level 2 (past
 * the policy's level-2 threshold) to an admin of the municipality — the
 * production mayor/CEO role, which does not exist as a distinct role yet.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaBreachScanner {

    /** Complaint statuses still on the clock. */
    public static final List<String> ACTIVE_STATUSES = List.of(
            "SUBMITTED", "VERIFIED", "ASSIGNED", "IN_PROGRESS", "REOPENED");

    private final SlaInstanceRepository instanceRepository;
    private final SlaBreachRepository breachRepository;
    private final OutboxRepository outboxRepository;
    private final NotificationMessageRepository notificationRepository;
    private final NotificationTemplateService templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /** One scan pass; returns the number of breaches recorded. */
    @Transactional
    public int scanOnce() {
        Instant now = clock.instant();
        List<Number> ids = overdueInstanceIds(now);
        int detected = 0;
        for (Number id : ids) {
            SlaInstance instance = instanceRepository.findById(id.longValue()).orElse(null);
            if (instance == null || instance.isBreached()) {
                continue;
            }
            if (recordBreach(instance, now)) {
                detected++;
            }
        }
        return detected;
    }

    /** Clear the active breach when its complaint resolves (called by ResolveHandler). */
    @Transactional
    public void clearBreachOnResolve(Long complaintId, Instant now) {
        breachRepository.findByComplaintIdAndResolvedAtIsNull(complaintId)
                .ifPresent(breach -> {
                    breach.setResolvedAt(now);
                    breachRepository.save(breach);
                });
    }

    @SuppressWarnings("unchecked")
    private List<Number> overdueInstanceIds(Instant now) {
        String placeholders = String.join(",",
                ACTIVE_STATUSES.stream().map(status -> "'" + status + "'").toList());
        return entityManager.createNativeQuery(
                "SELECT i.id FROM sla_instances i"
                        + " JOIN complaints c ON c.id = i.complaint_id"
                        + " WHERE i.deadline_at < :now"
                        + " AND i.breach_at IS NULL"
                        + " AND c.status IN (" + placeholders + ")"
                        + " ORDER BY i.id"
                        + " FOR UPDATE OF i SKIP LOCKED")
                .setParameter("now", now)
                .getResultList();
    }

    private boolean recordBreach(SlaInstance instance, Instant now) {
        Complaint complaint = instance.getComplaint();
        double overdueHours = Math.max(0.0,
                Duration.between(instance.getDeadlineAt(), now).toMinutes() / 60.0);
        int level = escalationLevel(instance, overdueHours);
        User recipient = escalationRecipient(complaint, level);

        instance.setBreachAt(now);
        instanceRepository.save(instance);
        try {
            breachRepository.saveAndFlush(SlaBreach.builder()
                    .slaInstance(instance)
                    .complaint(complaint)
                    .detectedAt(now)
                    .hoursOverdue(BigDecimal.valueOf(overdueHours).setScale(2, RoundingMode.HALF_UP))
                    .escalationLevel(level)
                    .escalatedTo(recipient)
                    .build());
        } catch (DataIntegrityViolationException alreadyRecorded) {
            log.debug("Breach for complaint {} already recorded; converging", complaint.getId());
            return false;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("complaintId", complaint.getId());
        payload.put("referenceCode", complaint.getReferenceCode());
        payload.put("occurredAt", now.toString());
        payload.put("escalationLevel", level);
        payload.put("hoursOverdue", overdueHours);
        payload.put("deadline", instance.getDeadlineAt().toString());
        payload.put("explanation", level == 2
                ? "deadline exceeded by " + String.format("%.1f", overdueHours)
                + "h; escalated to municipal admin"
                : "deadline exceeded by " + String.format("%.1f", overdueHours)
                + "h; ward councilor notified");
        if (recipient != null) {
            payload.put("escalatedToUserId", recipient.getId());
            notifyRecipient(recipient, complaint, payload);
        } else {
            payload.putNull("escalatedToUserId");
        }
        outboxRepository.save(OutboxMessage.builder()
                .aggregateType("COMPLAINT")
                .aggregateId(complaint.getId())
                .eventType("SLA_ESCALATION")
                .payload(write(payload))
                .status(OutboxMessage.STATUS_PENDING)
                .retryCount(0)
                .nextAttemptAt(now)
                .build());
        return true;
    }

    private int escalationLevel(SlaInstance instance, double overdueHours) {
        Integer level2 = instance.getPolicy() != null
                ? instance.getPolicy().getEscalationLevel2Hours() : null;
        return level2 != null && overdueHours > level2 ? 2 : 1;
    }

    private User escalationRecipient(Complaint complaint, int level) {
        Long municipalityId = complaint.getMunicipality().getId();
        Long wardId = complaint.getWard() != null ? complaint.getWard().getId() : null;
        UserRole role = level == 2 ? UserRole.ADMIN : UserRole.WARD_COUNCILOR;
        List<User> candidates = usersInRole(municipalityId, role);
        if (candidates.isEmpty()) {
            return null;
        }
        if (wardId != null) {
            for (User candidate : candidates) {
                if (servesWard(candidate.getId(), wardId)) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private List<User> usersInRole(Long municipalityId, UserRole role) {
        // No DISTINCT: Postgres rejects ORDER BY expressions absent from a
        // DISTINCT select list, and duplicates are harmless (first match wins).
        return entityManager.createQuery("""
                select m.user from UserMunicipalityMembership m
                where m.municipality.id = :municipalityId
                  and m.validUntil is null
                  and m.user.role = :role
                  and m.user.active = true
                order by m.user.id asc
                """, User.class)
                .setParameter("municipalityId", municipalityId)
                .setParameter("role", role)
                .getResultList();
    }

    private boolean servesWard(Long userId, Long wardId) {
        return entityManager.createQuery("""
                select count(m) from UserMunicipalityMembership m
                where m.user.id = :userId
                  and m.ward.id = :wardId
                  and m.validUntil is null
                """, Long.class)
                .setParameter("userId", userId)
                .setParameter("wardId", wardId)
                .getSingleResult() > 0;
    }

    private void notifyRecipient(User recipient, Complaint complaint, ObjectNode payload) {
        String text = templates.render("SLA_ESCALATION",
                NotificationTemplateService.DEFAULT_LOCALE, Map.of(
                        "referenceCode", complaint.getReferenceCode(),
                        "status", complaint.getStatus().name(),
                        "note", payload.path("explanation").asText("")));
        notificationRepository.save(NotificationMessage.builder()
                .user(recipient)
                .complaint(complaint)
                .channel(NotificationChannel.IN_APP)
                .templateCode("SLA_ESCALATION")
                .title("SLA escalation")
                .message(text)
                .build());
    }

    private String write(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize SLA payload", e);
        }
    }

    /** For tests: overdue, unbreached, active instance ids visible right now. */
    @Transactional(readOnly = true)
    public List<Long> overdueInstanceIdsForTest() {
        return overdueInstanceIds(clock.instant()).stream().map(Number::longValue).toList();
    }
}
