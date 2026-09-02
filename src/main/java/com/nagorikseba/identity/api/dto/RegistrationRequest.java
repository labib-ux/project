package com.nagorikseba.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Citizen self-registration payload.
 *
 * <p>The phone pattern accepts both the international and local spellings; the
 * service normalizes to the canonical {@code 01XXXXXXXXX} before persisting.
 */
public record RegistrationRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 120) String email,
        @Pattern(regexp = "^(?:\\+8801|8801|01)[3-9]\\d{8}$",
                message = "Enter a valid Bangladeshi mobile number") String phone,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
