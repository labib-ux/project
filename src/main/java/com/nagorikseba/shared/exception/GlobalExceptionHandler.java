package com.nagorikseba.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blueprint S18 — every exception the API can produce, mapped to an RFC-7807
 * {@code application/problem+json} body with the status the contract promises.
 *
 * <p>Status contract (§12 Phase 2, task 8):
 * <ul>
 *   <li>400 — request/validation problems (field errors included)</li>
 *   <li>401 — missing, invalid or expired credentials / refresh token</li>
 *   <li>403 — authenticated but not permitted (incl. tenancy violations)</li>
 *   <li>404 — resource not found</li>
 *   <li>409 — conflict: uniqueness or stale optimistic-lock version</li>
 *   <li>422 — domain refuses the state transition</li>
 *   <li>423 — account locked by failed-login throttling</li>
 * </ul>
 *
 * <p>Detail strings for credential failures are deliberately uniform so they cannot
 * be used to enumerate accounts. Passwords and tokens are never logged.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final Clock clock;

    // ---------------------------------------------------------------- 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
                                                    HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", request,
                fieldErrors(exception.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBinding(BindException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", request,
                fieldErrors(exception.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception,
                                                              HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            fieldErrors.putIfAbsent(String.valueOf(violation.getPropertyPath()), violation.getMessage());
        }
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", request, fieldErrors);
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ApiError> handleFileStorage(FileStorageException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-upload", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception,
                                                          HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", exception.getMessage(), request, Map.of());
    }

    // ---------------------------------------------------------------- 401
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> handleInvalidRefreshToken(InvalidRefreshTokenException exception,
                                                              HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-refresh-token", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception,
                                                         HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-credentials",
                "Invalid email/phone or password", request, Map.of());
    }

    /** Catch-all for the remaining {@code AuthenticationException}s (never leaks their message). */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception,
                                                         HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required", request, Map.of());
    }

    // ---------------------------------------------------------------- 403
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "access-denied",
                "You do not have permission to perform this action", request, Map.of());
    }

    // ---------------------------------------------------------------- 404
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", exception.getMessage(), request, Map.of());
    }

    // ---------------------------------------------------------------- 409
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "conflict", exception.getMessage(), request, Map.of());
    }

    /** R8 — {@code @Version} mismatch: the caller acted on a stale copy. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLocking(OptimisticLockingFailureException exception,
                                                             HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification",
                "This record was changed by someone else. Reload and try again.", request, Map.of());
    }

    /**
     * Unique-constraint races that slipped past the pre-checks (two registrations
     * with the same phone in the same instant). Constraint text is never echoed.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception,
                                                        HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(),
                exception.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "conflict",
                "That value is already in use", request, Map.of());
    }

    // ---------------------------------------------------------------- 422
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(InvalidStateTransitionException exception,
                                                            HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-state-transition",
                exception.getMessage(), request, Map.of());
    }

    // ---------------------------------------------------------------- 423
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiError> handleAccountLocked(AccountLockedException exception,
                                                        HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.LOCKED, "account-locked", exception.getMessage(),
                request.getRequestURI(), Map.of(), clock.instant());
        return ResponseEntity.status(HttpStatus.LOCKED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(body);
    }

    private Map<String, String> fieldErrors(java.util.List<FieldError> errors) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : errors) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return fieldErrors;
    }

    private ResponseEntity<ApiError> problem(HttpStatus status, String problemType, String detail,
                                             HttpServletRequest request, Map<String, String> fieldErrors) {
        ApiError body = ApiError.of(status, problemType,
                detail == null ? status.getReasonPhrase() : detail,
                request.getRequestURI(), fieldErrors, clock.instant());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
