package com.nagorikseba.notification.api;

import com.nagorikseba.notification.NotificationMessage;
import com.nagorikseba.notification.NotificationMessageRepository;
import com.nagorikseba.notification.api.dto.NotificationResponse;
import com.nagorikseba.shared.exception.ResourceNotFoundException;
import com.nagorikseba.shared.security.PrincipalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The caller's own in-app notifications (§3.5, N9).
 *
 * <p>Scoped strictly to {@code principalContext.requireUserId()} on every path:
 * a notification is personal, so there is no user id in any request to tamper
 * with. Marking someone else's notification read answers 404 rather than 403 —
 * the row is not merely forbidden, it does not exist in this caller's namespace,
 * and a 403 would confirm that the id is real.
 *
 * <p>{@code @Transactional} on the read paths because {@code complaint} is LAZY
 * and {@link NotificationResponse#from} dereferences it; without an open session
 * the mapping would throw after the controller returned.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /** Hard ceiling on page size — the bell only ever shows the newest few. */
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationMessageRepository notificationRepository;
    private final PrincipalContext principalContext;
    private final Clock clock;

    /**
     * Newest first. {@code Complaint -> notification} LAZY is fetched inside the
     * transaction rather than per-row, so the page costs one extra join, not N+1.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<NotificationResponse> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {

        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<NotificationMessage> rows = unreadOnly
                ? notificationRepository.findUnreadByUserIdOrderByIdDesc(
                        principalContext.requireUserId(), PageRequest.of(0, safeLimit))
                : notificationRepository.findByUserIdOrderByIdDesc(
                        principalContext.requireUserId(), PageRequest.of(0, safeLimit));
        return rows.stream().map(NotificationResponse::from).toList();
    }

    /** Unread badge count. Separate from {@link #list} so polling stays one cheap COUNT. */
    @GetMapping("/unread-count")
    @Transactional(readOnly = true)
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationRepository.countByUserIdAndReadFalse(
                principalContext.requireUserId()));
    }

    /** Mark one read. Idempotent: re-reading an already-read row is not an error. */
    @PostMapping("/{id}/read")
    @Transactional
    public NotificationResponse markRead(@PathVariable Long id) {
        Long userId = principalContext.requireUserId();
        NotificationMessage notification = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        notification.markRead(clock.instant());
        notificationRepository.save(notification);
        return NotificationResponse.from(notification);
    }

    /** Mark everything read — the "clear all" affordance on the bell dropdown. */
    @PostMapping("/read-all")
    @Transactional
    public Map<String, Long> markAllRead() {
        Long userId = principalContext.requireUserId();
        int updated = notificationRepository.markAllReadForUser(userId, clock.instant());
        return Map.of("updated", (long) updated);
    }
}