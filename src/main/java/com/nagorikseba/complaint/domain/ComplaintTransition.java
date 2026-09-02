package com.nagorikseba.complaint.domain;

import com.nagorikseba.complaint.domain.enums.ComplaintAction;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One row per state change — the append-only lifecycle audit log (§4.2).
 *
 * <p>No setters. A transition is written once by
 * {@code ComplaintLifecycleService.execute()} and never updated or deleted, which
 * is what makes the timeline trustworthy as evidence of who did what and when.
 * {@code setComplaint} is package-private purely so
 * {@link Complaint#addTransition} can close the bidirectional link.
 *
 * <p>{@code idempotencyKey} is unique per complaint
 * ({@code uq_transition_idempotency}), so a replayed action cannot double-write.
 */
@Entity
@Table(name = "complaint_transitions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplaintTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    /** Null only for the initial SUBMIT — there is no prior status to record. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ComplaintStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private ComplaintStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private ComplaintAction action;

    /** Null for anonymous submissions; {@code actorRole} then reads SYSTEM. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /**
     * Supplied by the caller from the {@code Clock} bean rather than generated, so
     * the seeder can lay down a realistic historical timeline and tests can pin it.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    void setComplaint(Complaint complaint) {
        this.complaint = complaint;
    }
}
