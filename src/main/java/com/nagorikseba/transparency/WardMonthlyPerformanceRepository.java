package com.nagorikseba.transparency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WardMonthlyPerformanceRepository extends JpaRepository<WardMonthlyPerformance, Long> {

    Optional<WardMonthlyPerformance> findByWardIdAndPeriodStart(Long wardId, LocalDate periodStart);

    List<WardMonthlyPerformance> findByMunicipalityIdAndPeriodStartOrderByResolvedComplaintsDesc(
            Long municipalityId, LocalDate periodStart);

    /**
     * Newest period that actually holds rows for this municipality.
     *
     * <p>The public scoreboard falls back to this when no {@code period} is
     * requested, so the ward table and landing hero numbers show the most recent
     * real month instead of an empty current month on the 1st of a new one.
     */
    @Query("select max(p.periodStart) from WardMonthlyPerformance p where p.municipality.id = :municipalityId")
    Optional<LocalDate> findLatestPeriodStart(@Param("municipalityId") Long municipalityId);
}
