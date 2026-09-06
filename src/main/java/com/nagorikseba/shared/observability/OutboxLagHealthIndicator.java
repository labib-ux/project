package com.nagorikseba.shared.observability;

import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Relay freshness probe (§10): DOWN when the oldest PENDING outbox row is
 * older than 10 minutes — the symptom of a stuck worker, a poison row, or a
 * dead downstream channel. Carries the lag in seconds either way so alerts can
 * threshold before it goes DOWN.
 */
@Component("outboxLag")
@RequiredArgsConstructor
public class OutboxLagHealthIndicator implements HealthIndicator {

    /** Seconds of oldest-pending age that flip the indicator DOWN. */
    public static final long LAG_THRESHOLD_SECONDS = 600L;

    private final OutboxRepository outboxRepository;
    private final Clock clock;

    @Override
    public Health health() {
        Instant now = clock.instant();
        List<OutboxMessage> oldest = outboxRepository.findClaimable(now, PageRequest.ofSize(1));
        if (oldest.isEmpty()) {
            return Health.up().withDetail("lagSeconds", 0).build();
        }
        long lagSeconds = Math.max(0L, Duration.between(oldest.get(0).getNextAttemptAt(), now).getSeconds());
        if (lagSeconds > LAG_THRESHOLD_SECONDS) {
            return Health.down()
                    .withDetail("lagSeconds", lagSeconds)
                    .withDetail("oldestPendingId", oldest.get(0).getId())
                    .withDetail("thresholdSeconds", LAG_THRESHOLD_SECONDS)
                    .build();
        }
        return Health.up().withDetail("lagSeconds", lagSeconds).build();
    }

    /** For tests and gauges: current oldest-pending age in seconds. */
    public long lagSeconds() {
        Instant now = clock.instant();
        List<OutboxMessage> oldest = outboxRepository.findClaimable(now, PageRequest.ofSize(1));
        if (oldest.isEmpty()) {
            return 0L;
        }
        return Math.max(0L, Duration.between(oldest.get(0).getNextAttemptAt(), now).getSeconds());
    }
}
