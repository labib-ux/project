package com.nagorikseba.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param identifier email (case-insensitive) or phone in any accepted spelling
 */
public record LoginRequest(
        @NotBlank @Size(max = 120) String identifier,
        @NotBlank @Size(max = 72) String password
) {
}
