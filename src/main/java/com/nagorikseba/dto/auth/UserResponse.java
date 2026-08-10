package com.nagorikseba.dto.auth;

import com.nagorikseba.enums.UserRole;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        UserRole role
) {
}
