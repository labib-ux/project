package com.nagorikseba.shared.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * The relay's claim query (Phase 5). Batch size comes in through
     * {@link Pageable} rather than a {@code :size} parameter — Spring Data rejects
     * a declared parameter that the query text never binds, which fails the whole
     * repository at bootstrap rather than at call time.
     */
    @Query("""
            SELECT o FROM OutboxMessage o
            WHERE o.status IN ('PENDING', 'FAILED')
              AND o.nextAttemptAt <= :now
            ORDER BY o.id
            """)
    List<OutboxMessage> findClaimable(@Param("now") Instant now, Pageable pageable);

    List<OutboxMessage> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, Long aggregateId);

    long countByStatus(String status);

    /**
     * Relay claim (R4/R5, §7.3): atomically move due rows to PROCESSING and
     * return them. The inner select locks with SKIP LOCKED, so concurrent
     * workers on any number of instances claim disjoint sets; the outer guard
     * on PENDING/FAILED keeps an already-claimed row from being taken twice.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_messages SET status = 'PROCESSING'
            WHERE id IN (
                SELECT id FROM outbox_messages
                WHERE status IN ('PENDING', 'FAILED')
                  AND next_attempt_at <= :now
                ORDER BY id
                LIMIT :size
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<OutboxMessage> claimBatch(@Param("size") int size, @Param("now") Instant now);
}
