package com.nagorikseba.notification;

import com.nagorikseba.shared.outbox.OutboxMessage;
import com.nagorikseba.shared.outbox.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Outbox relay (N6, §7.3, R5).
 *
 * <p>Every 10 s it claims due rows and dispatches each in its own transaction
 * on the shared executor: success marks SENT, failure bumps the retry count
 * with exponential backoff (1m, 2m, 4m, …) and parks the row FAILED after 5
 * attempts. Claiming and delivery are separate transactions on purpose — the
 * claim commits before dispatch starts, so parallel workers never block on
 * each other's uncommitted claims (each claims a disjoint set via SKIP
 * LOCKED). Self-invocation goes through the provider so the per-row
 * transaction boundaries actually apply.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class OutboxWorker {

    /** Attempts before a row is parked FAILED (terminal). */
    public static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository outboxRepository;
    private final NotificationDispatcher dispatcher;
    private final Executor outboxExecutor;
    private final ObjectProvider<OutboxWorker> self;
    private final Clock clock;

    public OutboxWorker(OutboxRepository outboxRepository,
                        NotificationDispatcher dispatcher,
                        @Qualifier("outboxExecutor") Executor outboxExecutor,
                        ObjectProvider<OutboxWorker> self,
                        Clock clock) {
        this.outboxRepository = outboxRepository;
        this.dispatcher = dispatcher;
        this.outboxExecutor = outboxExecutor;
        this.self = self;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:10000}",
            initialDelayString = "${app.outbox.poll-ms:10000}")
    public void poll() {
        try {
            int claimed = processBatch(50);
            if (claimed > 0) {
                log.debug("Outbox relay processed {} row(s)", claimed);
            }
        } catch (Exception e) {
            log.error("Outbox relay batch failed", e);
        }
    }

    /** Claim due rows, then deliver each on the executor and wait for all. */
    public int processBatch(int size) {
        List<Long> ids = self.getObject().claim(size, clock.instant()).stream()
                .map(OutboxMessage::getId).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        List<Future<?>> futures = new ArrayList<>(ids.size());
        for (Long id : ids) {
            futures.add(outboxExecutor.execute(() -> {});
            futures.remove(futures.size() - 1);
            futures.add(submit(id));
        }
        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Outbox dispatch task failed", e);
            }
        }
        return ids.size();
    }

    @Transactional
    public List<OutboxMessage> claim(int size, Instant now) {
        return outboxRepository.claimBatch(size, now);
    }

    /** Deliver one claimed row: SENT on success, backoff/FAILED on failure. */
    @Transactional
    public void processOne(Long id) {
        OutboxMessage message = outboxRepository.findById(id).orElse(null);
        if (message == null
                || (!OutboxMessage.STATUS_PENDING.equals(message.getStatus())
                && !"FAILED".equals(message.getStatus())
                && !"PROCESSING".equals(message.getStatus()))) {
            return;
        }
        Instant now = clock.instant();
        try {
            dispatcher.dispatch(message);
            message.setStatus("SENT");
            message.setProcessedAt(now);
            message.setLastError(null);
        } catch (Exception e) {
            int retries = message.getRetryCount() + 1;
            message.setRetryCount(retries);
            message.setLastError(truncated(e));
            if (retries >= MAX_ATTEMPTS) {
                message.setStatus(OutboxMessage.STATUS_FAILED);
            } else {
                message.setNextAttemptAt(now.plus(Duration.ofMinutes(1L << retries)));
            }
            log.warn("Outbox row {} delivery failed (attempt {}/{}): {}",
                    id, retries, MAX_ATTEMPTS, e.getMessage());
        }
        outboxRepository.save(message);
    }

    private Future<?> submit(Long id) {
        return ((java.util.concurrent.ExecutorService) outboxExecutor).submit(
                () -> self.getObject().processOne(id));
    }

    private static String truncated(Exception e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
