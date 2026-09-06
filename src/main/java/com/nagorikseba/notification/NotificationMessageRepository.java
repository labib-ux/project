package com.nagorikseba.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationMessageRepository extends JpaRepository<NotificationMessage, Long> {

    List<NotificationMessage> findByUserIdOrderByIdDesc(Long userId);

    long countByUserIdAndReadFalse(Long userId);

    boolean existsByOutboxIdAndUserId(Long outboxId, Long userId);
}
