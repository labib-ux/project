package com.nagorikseba.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily trigger for {@link DataRetentionJob}.
 *
 * <p>Kept separate from the job itself: the job bean is unconditional (the
 * admin controller injects it everywhere), only this <em>trigger</em> obeys
 * {@code app.scheduling.enabled} — same split as the SLA scanner and the
 * outbox relay.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class DataRetentionScheduler {

    private final DataRetentionJob job;

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        job.scheduledPurge();
    }
}
