package com.nagorikseba.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Blueprint S2 — RFC-7807 {@code application/problem+json} body.
 *
 * <p>{@code type}/{@code title}/{@code status}/{@code detail}/{@code instance} are
 * the RFC-7807 members. {@code timestamp} and {@code fieldErrors} are registered
 * extensions.
 *
 * <p>{@code error} and {@code message} are duplicates of {@code title} and
 * {@code detail}, kept because the shipped clients (static/js/auth.js and the
 * Phase 1 integration tests) read those two keys. Removing them is a breaking API
 * change and belongs to a versioned cleanup, not to this phase.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        Instant timestamp,
        String error,
        String message,
        Map<String, String> fieldErrors
) {

    private static final String TYPE_PREFIX = "urn:nagorik-seba:problem:";

    public static ApiError of(HttpStatus status, String problemType, String detail, String instance,
                              Map<String, String> fieldErrors, Instant timestamp) {
        String title = status.getReasonPhrase();
        return new ApiError(
                TYPE_PREFIX + problemType,
                title,
                status.value(),
                detail,
                instance,
                timestamp,
                title,
                detail,
                fieldErrors == null ? Map.of() : fieldErrors);
    }
}
