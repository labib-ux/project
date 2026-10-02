package com.nagorikseba.complaint.lifecycle;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.shared.config.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Daily AUTO_CLOSE trigger (§6: RESOLVED + 7 silent days → CLOSED).
 *
 * <p>Queries RESOLVED complaints past the grace cutoff and closes each
 * through {@code executeAsSystem} with a stable idempotency key
 * ({@code auto-close-<id>}), so overlapping runs — or a retry after a
 * crash — converge instead of duplicating. Per-row failures are logged and
 * skipped, never aborting the batch. Multi-instance safe via
 * {@link SchedulerLock}; off unless {@code app.scheduling.enabled=true}
 * like every other trigger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class AutoCloseScheduler {

    public static final String LOCK_NAME = "autoclose-job";

    private final ComplaintRepository complaintRepository;
    private final ComplaintLifecycleService lifecycleService;
    private final SchedulerLock schedulerLock;
    private final Clock clock;

    @Scheduled(cron = "${app.autoclose.cron:0 30 2 * * *}")
    public void scheduledAutoClose() {
        if (!schedulerLock.tryLock(LOCK_NAME)) {
            return;
        }
        try {
            int closed = autoCloseOnce();
            if (closed > 0) {
                log.info("AUTO_CLOSE closed {} complaint(s)", closed);
            }
        } catch (Exception e) {
            log.error("AUTO_CLOSE run failed", e);
        } finally {
            schedulerLock.unlock(LOCK_NAME);
        }
    }

    /** One pass over due complaints; returns the number closed. */
    public int autoCloseOnce() {
        var cutoff = clock.instant().minus(AutoCloseHandler.GRACE_PERIOD);
        List<Complaint> candidates = complaintRepository
                .findByStatusAndResolvedAtBefore(ComplaintStatus.RESOLVED, cutoff);
        int closed = 0;
        for (Complaint candidate : candidates) {
            try {
                lifecycleService.executeAsSystem(TransitionCommand.of(
                        ComplaintAction.AUTO_CLOSE,
                        candidate.getId(),
                        null,
                        "Auto-closed after 7 days without citizen rating",
                        "auto-close-" + candidate.getId(),
                        candidate.getVersion()));
                closed++;
            } catch (Exception e) {
                log.warn("AUTO_CLOSE failed for complaint {}; continuing",
                        candidate.getReferenceCode(), e);
            }
        }
        return closed;
    }
}
