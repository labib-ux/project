package com.nagorikseba.identity.api.dto;

/**
 * Result of register / login / refresh.
 *
 * @param accessToken  JWT, {@code expiresIn} seconds
 * @param tokenType    always {@code Bearer}
 * @param expiresIn    access-token lifetime in seconds
 * @param refreshToken opaque token; store it, send it to {@code /api/auth/refresh},
 *                     and replace it with the one returned there — the old value is
 *                     revoked on use and replaying it kills the whole session (R9)
 * @param user         the authenticated user
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        UserResponse user
) {

    public static final String BEARER = "Bearer";
}
