package com.nagorikseba.shared.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Multi-instance scheduler mutual exclusion without new dependencies.
 *
 * <p>Each scheduled trigger wraps its work in {@code tryLock(name)}: the
 * winner runs, losers skip. Backed by Postgres advisory locks, which release
 * automatically if the holder's connection dies — so a crashed instance can
 * never wedge the schedule. The upgrade path (ShedLock + Redis/JDBC) stays
 * open, but this is crash-safe and correct today.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerLock {

    private final JdbcTemplate jdbcTemplate;

    /** Returns true when this instance won the lock and must run the job. */
    public boolean tryLock(String lockName) {
        try {
            Boolean locked = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(hashtext(?))", Boolean.class, lockName);
            if (!Boolean.TRUE.equals(locked)) {
                log.info("Scheduler job '{}' already running elsewhere; skipping", lockName);
                return false;
            }
            return true;
        } catch (Exception e) {
            // Fail open: a lock-query failure must not silently stop SLA scans
            // or auto-closes. At worst two instances do idempotent work —
            // breach-once and idempotency-key guards converge anyway.
            log.warn("Advisory lock query failed for '{}'; running anyway", lockName, e);
            return true;
        }
    }

    public void unlock(String lockName) {
        try {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(hashtext('" + lockName.replace("'", "") + "'))");
        } catch (Exception e) {
            log.warn("Advisory unlock failed for '{}'", lockName, e);
        }
    }
}
