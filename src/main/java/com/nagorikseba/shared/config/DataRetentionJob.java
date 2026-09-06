package com.nagorikseba.shared.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Privacy retention (§9.4).
 *
 * <p>Daily: purges {@code anonymous_contact_phone} on terminal complaints
 * (CLOSED/REJECTED/CANCELLED) older than 90 days — follow-up need expires with
 * the case. Native queries keep the {@code Complaint} entity untouched (no new
 * mapped fields for columns only this job reads).
 *
 * <p>{@link #anonymizeComplaintsOf} backs user deletion: the caller's
 * complaints keep their history but lose the owner link, storing a SHA-256 of
 * the user id as an opaque dedup handle instead. Only this job's trigger obeys
 * {@code app.scheduling.enabled}; the methods stay callable everywhere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class DataRetentionJob {

    /** Days after terminal status before anonymous contact phones are purged. */
    public static final int ANONYMOUS_PHONE_RETENTION_DAYS = 90;

    @PersistenceContext
    private EntityManager entityManager;

    private final Clock clock;

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        try {
            int purged = purgeAnonymousPhones();
            if (purged > 0) {
                log.info("Retention purge cleared {} anonymous contact phone(s)", purged);
            }
        } catch (Exception e) {
            log.error("Retention purge failed", e);
        }
    }

    /** Purge due anonymous phones; returns rows cleared. */
    @Transactional
    public int purgeAnonymousPhones() {
        return entityManager.createNativeQuery("""
                UPDATE complaints SET anonymous_contact_phone = NULL
                WHERE anonymous_contact_phone IS NOT NULL
                  AND status IN ('CLOSED', 'REJECTED', 'CANCELLED')
                  AND submitted_at < :cutoff
                """)
                .setParameter("cutoff", clock.instant()
                        .minusSeconds((long) ANONYMOUS_PHONE_RETENTION_DAYS * 24 * 60 * 60))
                .executeUpdate();
    }

    /**
     * Detach every complaint of a deleted user, keeping an opaque dedup hash.
     * Returns complaints anonymized. The caller deactivates the user row itself.
     */
    @Transactional
    public int anonymizeComplaintsOf(Long userId) {
        String hash = sha256Hex("user:" + userId);
        return entityManager.createNativeQuery("""
                UPDATE complaints SET citizen_id = NULL, citizen_ref_hash = :hash
                WHERE citizen_id = :userId
                """)
                .setParameter("hash", hash)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
