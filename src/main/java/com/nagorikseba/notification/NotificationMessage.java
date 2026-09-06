package com.nagorikseba.notification;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.enums.NotificationChannel;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.shared.outbox.OutboxMessage;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * In-app notification row on the {@code notifications} table (§3.5, N9).
 *
 * <p>Explicit JPA entity name: the legacy {@code entity.Notification} maps the
 * same table and the default name would collide at bootstrap. The V5 migration
 * only <em>adds</em> nullable columns, so both mappings validate. New rows set
 * {@code outboxId} whenever they are written while dispatching an outbox row;
 * the partial unique constraint makes redelivery converge instead of
 * duplicating (R5).
 */
@Entity(name = "AppNotification")
@Table(name = "notifications")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complaint_id")
    private Complaint complaint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "template_code", length = 60)
    private String templateCode;

    @Column(length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(length = 10)
    @Builder.Default
    private String locale = NotificationTemplateService.DEFAULT_LOCALE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String payload;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "read_at", columnDefinition = "timestamptz")
    private Instant readAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outbox_id")
    private OutboxMessage outbox;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;
}
