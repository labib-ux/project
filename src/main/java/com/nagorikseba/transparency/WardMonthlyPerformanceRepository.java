package com.nagorikseba.transparency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WardMonthlyPerformanceRepository extends JpaRepository<WardMonthlyPerformance, Long> {

    Optional<WardMonthlyPerformance> findByWardIdAndPeriodStart(Long wardId, LocalDate periodStart);

    List<WardMonthlyPerformance> findByMunicipalityIdAndPeriodStartOrderByResolvedComplaintsDesc(
            Long municipalityId, LocalDate periodStart);
}
