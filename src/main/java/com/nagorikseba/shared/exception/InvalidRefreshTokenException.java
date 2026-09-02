package com.nagorikseba.shared.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * The presented refresh token is unknown, expired, or already rotated away.
 *
 * <p>Deliberately one exception for all three cases: the client learns only that
 * it must log in again, never why. Reuse of a rotated token additionally revokes
 * the whole token family before this is thrown (R9).
 */
public class InvalidRefreshTokenException extends AuthenticationException {

    private static final String MESSAGE = "Refresh token is invalid or expired. Please sign in again.";

    public InvalidRefreshTokenException() {
        super(MESSAGE);
    }

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
