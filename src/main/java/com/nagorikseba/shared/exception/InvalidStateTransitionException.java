package com.nagorikseba.shared.exception;

/**
 * Blueprint S19 — an action was requested that the aggregate's current state does
 * not allow (e.g. RESOLVE on a complaint that was never started).
 *
 * <p>Maps to 422 Unprocessable Entity: the request was well-formed and the caller
 * was allowed to make it, but the domain refuses the transition. Distinct from 409,
 * which means "your view of the resource is stale".
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
