package com.nagorikseba.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationMessageRepository extends JpaRepository<NotificationMessage, Long> {

    /**
     * Newest page for one user, unread included.
     *
     * <p>{@code complaint} is fetched in the same statement because the response DTO
     * dereferences it — {@code open-in-view} is off (§ application.yml), so a lazy
     * association left to the view layer would fail rather than quietly re-open a
     * session.
     *
     * <p>Queries name the entity {@code AppNotification}, not {@code NotificationMessage}:
     * the entity carries an explicit {@code @Entity(name = ...)} so it does not collide
     * at bootstrap with the legacy {@code entity.Notification} mapping on the same
     * table, and an explicit name replaces the class name in the JPQL namespace.
     */
    @Query("""
            select n from AppNotification n
            left join fetch n.complaint
            where n.user.id = :userId
            order by n.id desc
            """)
    List<NotificationMessage> findByUserIdOrderByIdDesc(@Param("userId") Long userId,
                                                        Pageable pageable);

    /** Unread-only variant, backed by {@code idx_notification_user_unread}. */
    @Query("""
            select n from AppNotification n
            left join fetch n.complaint
            where n.user.id = :userId and n.read = false
            order by n.id desc
            """)
    List<NotificationMessage> findUnreadByUserIdOrderByIdDesc(@Param("userId") Long userId,
                                                              Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    boolean existsByOutboxIdAndUserId(Long outboxId, Long userId);

    /**
     * Ownership-scoped lookup, so "mark read" cannot reach another user's row.
     *
     * <p>Deriving this from {@code findById} and checking in Java would also be
     * correct, but it would load the row first — scoping in the query means an
     * id belonging to someone else is simply absent.
     */
    @Query("""
            select n from AppNotification n
            left join fetch n.complaint
            where n.id = :id and n.user.id = :userId
            """)
    Optional<NotificationMessage> findByIdAndUserId(@Param("id") Long id,
                                                    @Param("userId") Long userId);

    /**
     * Bulk mark-read in one UPDATE.
     *
     * <p>A per-row loop here would issue one UPDATE per unread notification on a
     * long-dormant account; the set-based form is a single statement regardless of
     * how far behind the badge is. {@code read = false} in the WHERE keeps the
     * {@code readAt} stamp on rows that were genuinely unread.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AppNotification n
            set n.read = true, n.readAt = :now
            where n.user.id = :userId and n.read = false
            """)
    int markAllReadForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
