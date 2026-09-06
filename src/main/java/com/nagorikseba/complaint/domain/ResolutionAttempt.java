package com.nagorikseba.complaint.domain;

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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * One row per resolution cycle (§3.3).
 *
 * <p>The flat {@code rating}/{@code reopen_reason} columns lost history when a
 * complaint was resolved → reopened → resolved again; one row per attempt
 * preserves every cycle. Outcomes: PENDING_CITIZEN (awaiting rate/reopen),
 * CLOSED (rated), REOPENED (sent back). Unique per (complaint, attempt) keeps
 * double ratings (R10) impossible at the database level.
 */
@Entity
@Table(name = "resolution_attempts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolutionAttempt {

    public enum Outcome {
        PENDING_CITIZEN,
        CLOSED,
        REOPENED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resolved_by", nullable = false)
    private User resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private Outcome outcome;

    @Column
    @Setter
    private Integer rating;

    @Column(name = "rating_feedback", columnDefinition = "TEXT")
    @Setter
    private String ratingFeedback;

    @Column(name = "rated_at")
    @Setter
    private Instant ratedAt;

    @Column(name = "reopen_reason", columnDefinition = "TEXT")
    @Setter
    private String reopenReason;

    @Column(name = "reopened_at")
    @Setter
    private Instant reopenedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
