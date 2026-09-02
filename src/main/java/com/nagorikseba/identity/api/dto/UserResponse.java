package com.nagorikseba.identity.api.dto;

import com.nagorikseba.enums.UserRole;

import java.util.Set;

/**
 * Public projection of a user. Never carries the password hash, lockout counters
 * or timestamps.
 *
 * @param municipalityIds municipalities the user currently serves; empty for citizens
 */
public record UserResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        UserRole role,
        Set<Long> municipalityIds
) {
}
