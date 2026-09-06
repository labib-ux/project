package com.nagorikseba.sla;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Hourly trigger for {@link SlaBreachScanner}.
 *
 * <p>Kept separate from the scanner itself: the scanner bean is unconditional
 * (handlers and tests inject it everywhere), only this <em>trigger</em> obeys
 * {@code app.scheduling.enabled} — so background scans never disturb test
 * suites that did not opt in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class SlaBreachScheduler {

    private final SlaBreachScanner scanner;

    @Scheduled(cron = "${app.sla.scan-cron:0 0 * * * *}")
    public void scheduledScan() {
        try {
            int detected = scanner.scanOnce();
            if (detected > 0) {
                log.info("SLA scanner detected {} breach(es)", detected);
            }
        } catch (Exception e) {
            log.error("SLA scanner run failed", e);
        }
    }
}
