package com.nagorikseba.shared.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
