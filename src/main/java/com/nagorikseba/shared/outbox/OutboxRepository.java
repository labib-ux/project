package com.nagorikseba.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    @Query("SELECT o FROM OutboxMessage o WHERE o.status IN ('PENDING', 'FAILED') AND o.nextAttemptAt <= :now ORDER BY o.id")
    List<OutboxMessage> findPendingMessages(@Param("now") java.time.Instant now, @Param("size") int size);

    @Query("SELECT o FROM OutboxMessage o WHERE o.id IN :ids")
    List<OutboxMessage> findByIds(@Param("ids") List<Long> ids);
}