package com.nagorikseba.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Exchanges a valid refresh token for a new access/refresh pair (rotation). */
public record RefreshRequest(
        @NotBlank @Size(max = 200) String refreshToken
) {
}
