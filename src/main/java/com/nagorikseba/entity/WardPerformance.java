package com.nagorikseba.entity;

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

import java.math.BigDecimal;

@Entity
@Table(name = "ward_performance")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WardPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id", nullable = false)
    private Ward ward;

    @Column(name = "performance_month", nullable = false)
    private Integer month;

    @Column(name = "performance_year", nullable = false)
    private Integer year;

    @Column(name = "total_complaints", nullable = false)
    @Builder.Default
    private int totalComplaints = 0;

    @Column(name = "resolved_complaints", nullable = false)
    @Builder.Default
    private int resolvedComplaints = 0;

    @Column(name = "avg_resolution_hours", precision = 10, scale = 2)
    private BigDecimal avgResolutionHours;

    @Column(name = "avg_rating", precision = 3, scale = 2)
    private BigDecimal avgRating;

    @Column(name = "sla_breach_count", nullable = false)
    @Builder.Default
    private int slaBreachCount = 0;
}
