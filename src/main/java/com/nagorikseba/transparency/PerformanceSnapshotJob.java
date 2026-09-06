package com.nagorikseba.transparency;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.sla.SlaBreachRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Monthly transparency snapshots (T5, §3.6, R7).
 *
 * <p>Runs on the 1st at 02:00; overlaps (two instances, slow months) serialize
 * on {@code pg_try_advisory_lock(hashtext('ward-snapshot-job'))} — the loser
 * skips instead of double-computing. Per (ward, month) upserts converge on
 * {@code uq_ward_period}, so a retry never duplicates. Only scheduled when
 * {@code app.scheduling.enabled=true}; tests call {@link #snapshotMonth} (or
 * {@link #snapshotCurrentMonth}) directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
public class PerformanceSnapshotJob {

    private final WardMonthlyPerformanceRepository performanceRepository;
    private final WardRepository wardRepository;
    private final ComplaintRepository complaintRepository;
    private final SlaBreachRepository breachRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Scheduled(cron = "${app.snapshot.cron:0 0 2 1 * *}")
    public void scheduledSnapshot() {
        Boolean locked = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(hashtext('ward-snapshot-job'))", Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("Snapshot job already running elsewhere; skipping");
            return;
        }
        try {
            YearMonth previous = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
            int wards = snapshotMonth(previous.atDay(1));
            log.info("Snapshot for {} computed for {} ward(s)", previous, wards);
        } finally {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(hashtext('ward-snapshot-job'))");
        }
    }

    /** Snapshot every active ward for the month containing {@code anyDay}. */
    @Transactional
    public int snapshotCurrentMonth() {
        return snapshotMonth(LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1));
    }

    /** Snapshot every active ward for a month; returns wards snapshotted. */
    @Transactional
    public int snapshotMonth(LocalDate periodStart) {
        Instant monthStart = periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthEnd = periodStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int wards = 0;
        for (var ward : wardRepository.findAll()) {
            if (!Boolean.TRUE.equals(ward.getIsActive())) {
                continue;
            }
            List<Complaint> inMonth = complaintRepository.findAll().stream()
                    .filter(complaint -> complaint.getWard() != null
                            && complaint.getWard().getId().equals(ward.getId())
                            && !complaint.getSubmittedAt().isBefore(monthStart)
                            && complaint.getSubmittedAt().isBefore(monthEnd))
                    .toList();
            long resolved = inMonth.stream()
                    .filter(complaint -> complaint.getStatus() == ComplaintStatus.RESOLVED
                            || complaint.getStatus() == ComplaintStatus.CLOSED)
                    .count();
            Double avgHours = averageHours(inMonth);
            long breaches = breachRepository.findAll().stream()
                    .filter(breach -> breach.getComplaint() != null
                            && breach.getComplaint().getWard() != null
                            && breach.getComplaint().getWard().getId().equals(ward.getId())
                            && !breach.getDetectedAt().isBefore(monthStart)
                            && breach.getDetectedAt().isBefore(monthEnd))
                    .count();
            double reopenRate = inMonth.isEmpty() ? 0.0
                    : inMonth.stream().mapToInt(Complaint::getReopenCount).average().orElse(0.0);

            WardMonthlyPerformance row = performanceRepository
                    .findByWardIdAndPeriodStart(ward.getId(), periodStart)
                    .orElseGet(() -> WardMonthlyPerformance.builder()
                            .ward(ward)
                            .municipality(ward.getMunicipality())
                            .periodStart(periodStart)
                            .build());
            row.setTotalComplaints(inMonth.size());
            row.setResolvedComplaints((int) resolved);
            row.setAvgResolutionHours(avgHours != null
                    ? BigDecimal.valueOf(avgHours).setScale(2, RoundingMode.HALF_UP) : null);
            row.setSlaBreachCount((int) breaches);
            row.setReopenRate(BigDecimal.valueOf(reopenRate).setScale(4, RoundingMode.HALF_UP));
            performanceRepository.save(row);
            wards++;
        }
        return wards;
    }

    private Double averageHours(List<Complaint> complaints) {
        double[] hours = complaints.stream()
                .filter(complaint -> complaint.getResolvedAt() != null)
                .mapToDouble(complaint -> Duration.between(
                        complaint.getSubmittedAt(), complaint.getResolvedAt()).toMinutes() / 60.0)
                .toArray();
        if (hours.length == 0) {
            return null;
        }
        double sum = 0.0;
        for (double hour : hours) {
            sum += hour;
        }
        return sum / hours.length;
    }
}
