package com.nagorikseba.shared.exception;

import org.springframework.security.authentication.LockedException;

/**
 * Too many consecutive failed password attempts (§8.1: 5 failures → 15 min lock).
 *
 * <p>Extends Spring Security's {@link LockedException} so the standard
 * authentication semantics still apply, and carries the remaining lock time so the
 * handler can emit a {@code Retry-After} header alongside 423 Locked.
 */
public class AccountLockedException extends LockedException {

    private final long retryAfterSeconds;

    public AccountLockedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 0);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
