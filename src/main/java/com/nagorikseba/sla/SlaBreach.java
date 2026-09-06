package com.nagorikseba.sla;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.identity.domain.User;
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

/**
 * One breach detection per complaint at a time (§3.4, L3).
 *
 * <p>Breach-once is enforced by the partial unique index
 * {@code uq_breach_active_per_complaint} (complaint WHERE resolved_at IS
 * NULL): concurrent scanner runs cannot double-insert, they collide on the
 * constraint instead. Clearing sets {@code resolvedAt}, never deletes the row.
 */
@Entity
@Table(name = "sla_breaches")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaBreach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sla_instance_id", nullable = false)
    private SlaInstance slaInstance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    /** Always supplied by the caller from the {@code Clock} bean. */
    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "hours_overdue", nullable = false, precision = 6, scale = 2)
    private BigDecimal hoursOverdue;

    @Column(name = "escalation_level", nullable = false)
    @Builder.Default
    private int escalationLevel = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalated_to_user_id")
    private User escalatedTo;

    @Column(name = "resolved_at")
    @Setter
    private Instant resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isActive() {
        return resolvedAt == null;
    }
}
