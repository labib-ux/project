package com.nagorikseba.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * {@code outbox.lag.seconds} gauge (§10): age of the oldest claimable outbox
 * row, scraped on demand. Shares the query with the health indicator so both
 * signals always agree.
 */
@Component
@RequiredArgsConstructor
public class OutboxLagGauge implements MeterBinder {

    private final OutboxLagHealthIndicator healthIndicator;

    @Override
    public void bindTo(@NonNull MeterRegistry registry) {
        Gauge.builder("outbox.lag.seconds", healthIndicator, OutboxLagHealthIndicator::lagSeconds)
                .description("Age in seconds of the oldest claimable outbox message")
                .register(registry);
    }
}
