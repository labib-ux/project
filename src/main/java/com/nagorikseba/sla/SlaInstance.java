package com.nagorikseba.sla;

import com.nagorikseba.complaint.domain.Complaint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Materialized per-complaint SLA state (§3.4, L2).
 *
 * <p>One row per complaint (unique). The deadline is a snapshot — later policy
 * edits do not move it; only priority changes and reopens recalculate it via
 * {@code SlaService}. {@code breachAt} is stamped once by the scanner; the
 * breach detail lives in {@code sla_breaches}.
 */
@Entity
@Table(name = "sla_instances")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false, unique = true)
    private Complaint complaint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    @Setter
    private SlaPolicy policy;

    @Column(name = "deadline_at", nullable = false)
    @Setter
    private Instant deadlineAt;

    @Column(name = "warn_at")
    @Setter
    private Instant warnAt;

    @Column(name = "breach_at")
    @Setter
    private Instant breachAt;

    @Column(name = "last_calculated_at", nullable = false)
    @Setter
    private Instant lastCalculatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isBreached() {
        return breachAt != null;
    }
}
