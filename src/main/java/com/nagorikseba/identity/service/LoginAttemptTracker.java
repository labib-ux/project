package com.nagorikseba.identity.service;

import com.nagorikseba.identity.config.LockoutProperties;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Failed-login bookkeeping in its own transaction.
 *
 * <p>A failed login has to <em>persist</em> the incremented counter and then report
 * the failure by throwing. If both happened in one transaction, the throw would roll
 * the counter back and lockout could never trigger. Every method here therefore runs
 * {@link Propagation#REQUIRES_NEW} and commits before the caller throws.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptTracker {

    private final UserRepository userRepository;
    private final LockoutProperties lockoutProperties;
    private final Clock clock;

    /**
     * Outcome of a failed attempt.
     *
     * @param lockedNow   true when <em>this</em> attempt crossed the threshold
     * @param lockedUntil when the lock expires, or {@code null} if not locked
     */
    public record LockState(boolean lockedNow, Instant lockedUntil) {

        static LockState unlocked() {
            return new LockState(false, null);
        }
    }

    /** Increments the counter and locks the account if the threshold is reached. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LockState recordFailure(Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return LockState.unlocked();
        }
        User user = found.get();
        Instant now = clock.instant();
        boolean lockedNow = user.registerFailedLogin(
                lockoutProperties.getMaxFailedAttempts(), lockoutProperties.getDuration(), now);
        userRepository.save(user);
        return new LockState(lockedNow, user.getLockedUntil());
    }

    /** Clears the counter and stamps {@code last_login_at}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.registerSuccessfulLogin(clock.instant());
            userRepository.save(user);
        });
    }
}
