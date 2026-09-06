package com.nagorikseba.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 10-second trigger for {@link OutboxWorker}.
 *
 * <p>Kept separate from the worker itself: the worker bean is unconditional
 * (tests drive {@code processBatch}/{@code processOne} directly everywhere),
 * only this <em>trigger</em> obeys {@code app.scheduling.enabled} — so the
 * background relay never steals rows from suites that did not opt in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class OutboxRelayScheduler {

    private final OutboxWorker worker;

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:10000}",
            initialDelayString = "${app.outbox.poll-ms:10000}")
    public void poll() {
        try {
            int claimed = worker.processBatch(50);
            if (claimed > 0) {
                log.debug("Outbox relay processed {} row(s)", claimed);
            }
        } catch (Exception e) {
            log.error("Outbox relay batch failed", e);
        }
    }
}
