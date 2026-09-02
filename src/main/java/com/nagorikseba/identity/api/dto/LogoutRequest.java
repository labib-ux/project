package com.nagorikseba.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Revokes the presented refresh token so the session cannot be resumed.
 *
 * <p>The access token is not revoked — it dies with its 15-minute expiry.
 */
public record LogoutRequest(
        @NotBlank @Size(max = 200) String refreshToken
) {
}
