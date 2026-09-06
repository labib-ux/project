package com.nagorikseba.transparency;

import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Monthly transparency snapshot per ward (§3.6, T5).
 *
 * <p>One row per (ward, first-of-month), upserted by
 * {@code PerformanceSnapshotJob}. Unique + count-check constraints make
 * double snapshots converge instead of duplicating.
 */
@Entity
@Table(name = "ward_monthly_performance")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WardMonthlyPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ward_id", nullable = false)
    private Ward ward;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "municipality_id", nullable = false)
    private Municipality municipality;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "total_complaints", nullable = false)
    @Builder.Default
    private int totalComplaints = 0;

    @Column(name = "resolved_complaints", nullable = false)
    @Builder.Default
    @Setter
    private int resolvedComplaints = 0;

    @Column(name = "avg_resolution_hours", precision = 10, scale = 2)
    @Setter
    private BigDecimal avgResolutionHours;

    @Column(name = "avg_rating", precision = 3, scale = 2)
    @Setter
    private BigDecimal avgRating;

    @Column(name = "sla_breach_count", nullable = false)
    @Builder.Default
    @Setter
    private int slaBreachCount = 0;

    @Column(name = "reopen_rate", precision = 5, scale = 4)
    @Setter
    private BigDecimal reopenRate;

    @CreationTimestamp
    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;
}
