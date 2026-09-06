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
        LocalDate periodStart = parsePeriod(period);
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
