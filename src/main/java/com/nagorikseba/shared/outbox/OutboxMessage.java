package com.nagorikseba.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A durable side effect, written in the same transaction as the state change that
 * caused it (§7.3 transactional outbox).
 *
 * <p>The point is atomicity: a complaint cannot reach VERIFIED without its
 * notification event also being committed, and no event can exist for a
 * transition that rolled back. A relay worker (Phase 5) later claims PENDING rows
 * and delivers them, so a downstream outage delays notifications instead of
 * losing them or poisoning the write path.
 *
 * <p>Phase 3 only accumulates rows — nothing drains them yet.
 */
@Entity
@Table(name = "outbox_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    /** PENDING | PUBLISHED | FAILED | DEAD — constrained by {@code ck_outbox_status}. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /**
     * When the relay may next attempt delivery. Always supplied by the caller from
     * the {@code Clock} bean — a field initializer here would be silently dropped
     * by {@code @Builder} and would read the wall clock besides.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD = "DEAD";
}
