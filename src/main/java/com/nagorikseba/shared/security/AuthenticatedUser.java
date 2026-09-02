package com.nagorikseba.shared.security;

import com.nagorikseba.enums.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The typed principal carried by every authenticated {@code /api/**} request.
 *
 * <p>Built purely from access-token claims — no database round-trip per request.
 * {@link #getUsername()} returns the canonical identifier (email when the account
 * has one, otherwise the normalized phone), which is also the token's {@code sub}.
 *
 * @param id              {@code uid} claim — the user's primary key
 * @param identifier      {@code sub} claim — canonical email or phone
 * @param role            {@code role} claim
 * @param municipalityIds {@code mids} claim — municipalities the user currently serves
 */
public record AuthenticatedUser(
        Long id,
        String identifier,
        UserRole role,
        Set<Long> municipalityIds
) implements UserDetails {

    public AuthenticatedUser(Long id, String identifier, UserRole role, Collection<Long> municipalityIds) {
        this(id, identifier, role,
                municipalityIds == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(municipalityIds)));
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean servesMunicipality(Long municipalityId) {
        return municipalityId != null && municipalityIds.contains(municipalityId);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** Never available from a token — credentials are not carried in the principal. */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return identifier;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
