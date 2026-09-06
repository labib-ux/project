package com.nagorikseba.complaint.domain;

import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Department;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Assignment history: who had a complaint, when (§3.3, Phase 4).
 *
 * <p>One row per assignment, never updated except to close it: reassignment
 * closes the current row ({@link #close}) and opens a new one, so the full
 * chain of who held a complaint stays queryable for analytics. A row with
 * {@code unassignedAt == null} is the current assignment; the partial index
 * {@code idx_assignment_complaint_active} backs that lookup.
 *
 * <p>{@code strategyUsed} names the routing strategy that produced the row
 * (CATEGORY, LOAD_BALANCED, DISTANCE, MANUAL); {@code strategyExplanation} is
 * the human-readable audit trail (matched category / least load count /
 * distance km) mirrored into the transition {@code metadata} JSONB.
 */
@Entity
@Table(name = "complaint_assignments")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "officer_id")
    private User officer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "strategy_used", length = 30)
    private String strategyUsed;

    @Column(name = "strategy_explanation", columnDefinition = "TEXT")
    private String strategyExplanation;

    @Column(name = "unassigned_at")
    private Instant unassignedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isActive() {
        return unassignedAt == null;
    }

    /** Closes the assignment; the row is kept as history. */
    public void close(Instant now) {
        unassignedAt = now;
    }
}
