package com.nagorikseba.transparency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monthly per-ward rankings from transparency snapshots (T4, §3.6).
 *
 * <p>Reads only — rows are written by {@code PerformanceSnapshotJob}.
 * {@code period} is {@code yyyy-MM}; score is resolution rate
 * (resolved × 100 / total), ranked best first.
 */
@Service
@RequiredArgsConstructor
public class ScoreboardService {

    private final WardMonthlyPerformanceRepository performanceRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> scoreboard(Long municipalityId, String period) {
        LocalDate periodStart = resolvePeriod(municipalityId, period);
        return performanceRepository
                .findByMunicipalityIdAndPeriodStartOrderByResolvedComplaintsDesc(
                        municipalityId, periodStart)
                .stream()
                .map(row -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("wardId", row.getWard().getId());
                    entry.put("wardNumber", row.getWard().getWardNumber());
                    entry.put("areaName", row.getWard().getAreaName());
                    entry.put("totalComplaints", row.getTotalComplaints());
                    entry.put("resolvedComplaints", row.getResolvedComplaints());
                    double rate = row.getTotalComplaints() == 0 ? 0.0
                            : row.getResolvedComplaints() * 100.0 / row.getTotalComplaints();
                    entry.put("resolutionRate", Math.round(rate * 100.0) / 100.0);
                    entry.put("averageResolutionHours", row.getAvgResolutionHours());
                    entry.put("slaBreachCount", row.getSlaBreachCount());
                    return entry;
                })
                .toList();
    }

    /**
     * Resolves the month to report on.
     *
     * <p>An explicit {@code period} is always honoured. Otherwise the default is
     * the most recent <em>completed</em> calendar month — the current month is
     * still accumulating, so scoring it early in the month reports near-zero
     * totals and a 0% resolution rate, which is both misleading and a bad thing
     * to put on a screen. If that month has no snapshot rows yet (a database
     * seeded mid-month), we fall back to the newest month that does.
     */
    private LocalDate resolvePeriod(Long municipalityId, String period) {
        if (period != null && !period.isBlank()) {
            return parsePeriod(period);
        }
        LocalDate lastCompletedMonth = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .minusMonths(1).withDayOfMonth(1);
        boolean hasRowsForLastCompletedMonth = !performanceRepository
                .findByMunicipalityIdAndPeriodStartOrderByResolvedComplaintsDesc(
                        municipalityId, lastCompletedMonth).isEmpty();
        if (hasRowsForLastCompletedMonth) {
            return lastCompletedMonth;
        }
        return performanceRepository.findLatestPeriodStart(municipalityId)
                .orElse(lastCompletedMonth);
    }

    /** First day of the requested month; defaults to the current month. */
    public LocalDate parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        }
        try {
            return YearMonth.parse(period.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Period must be yyyy-MM, e.g. 2026-09");
        }
    }
}
