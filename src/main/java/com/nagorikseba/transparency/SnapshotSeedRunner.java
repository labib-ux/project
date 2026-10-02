package com.nagorikseba.transparency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Boot-time scoreboard seed (follow-up to Phase 6 §(e): a fresh database
 * showed an all-zeros scoreboard until the monthly job ran).
 *
 * <p>Runs after {@code DataSeeder} (explicit order beats its default) and
 * snapshots a trailing window of months when the performance table is empty —
 * so the public scoreboard and ward pages are non-trivial from first boot.
 *
 * <p>Why a window and not just the current month: the seeder backdates demo
 * complaints across the previous months (a 12-hours-ago complaint lands in the
 * prior month on the 1st), so snapshotting only "now" produced a board of
 * zeroes on exactly the day it was demonstrated. The public scoreboard defaults
 * to the most recent month that actually has rows, so a trailing window is what
 * makes the landing hero numbers and ward table show real values.
 *
 * <p>Never overwrites: a non-empty table means the monthly job (or a previous
 * boot) already owns the data. Off unless {@code app.snapshot.seed-on-boot=true};
 * dev enables it, tests and prod leave it to the scheduled job.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(20)
@ConditionalOnProperty(name = "app.snapshot.seed-on-boot", havingValue = "true", matchIfMissing = false)
public class SnapshotSeedRunner implements CommandLineRunner {

    /** How many trailing months to backfill on a fresh database. */
    public static final int TRAILING_MONTHS = 6;

    private final WardMonthlyPerformanceRepository performanceRepository;
    private final PerformanceSnapshotJob snapshotJob;

    @Override
    public void run(String... args) {
        if (performanceRepository.count() > 0) {
            log.debug("Ward performance snapshots present; skipping boot seed");
            return;
        }
        YearMonth current = YearMonth.now();
        int wards = 0;
        for (int back = TRAILING_MONTHS - 1; back >= 0; back--) {
            LocalDate periodStart = current.minusMonths(back).atDay(1);
            wards = snapshotJob.snapshotMonth(periodStart);
        }
        log.info("Seeded {} trailing month(s) of ward performance snapshots ({} wards each)",
                TRAILING_MONTHS, wards);
    }
}
