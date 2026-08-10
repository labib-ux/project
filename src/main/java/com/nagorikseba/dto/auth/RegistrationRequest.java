package com.nagorikseba.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Email @Size(max = 100) String email,
        @Pattern(regexp = "^(?:\\+8801|01)[3-9]\\d{8}$", message = "Enter a valid Bangladeshi mobile number") String phone,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
