package com.nagorikseba.shared.security;

import com.nagorikseba.enums.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Blueprint S9 — typed principal + municipality resolution for service code.
 *
 * <p>The single place that reads {@link SecurityContextHolder}. Services ask this
 * component "who is calling?" and "may they touch municipality X?" instead of
 * casting {@code Authentication#getPrincipal()} themselves, which is how tenancy
 * checks get forgotten.
 *
 * <p>Resolution is claim-based: user id, role and municipality ids all come from
 * the verified access token, so a tenancy check costs no query.
 */
@Component
public class PrincipalContext {

    /** Empty when the request is anonymous (public endpoints, Thymeleaf pages). */
    public Optional<AuthenticatedUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthenticatedUser principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /**
     * @throws AuthenticationCredentialsNotFoundException if the call is anonymous;
     *         mapped to 401 by {@code GlobalExceptionHandler}
     */
    public AuthenticatedUser requireUser() {
        return currentUser().orElseThrow(
                () -> new AuthenticationCredentialsNotFoundException("Authentication required"));
    }

    public boolean isAuthenticated() {
        return currentUser().isPresent();
    }

    public Optional<Long> currentUserId() {
        return currentUser().map(AuthenticatedUser::id);
    }

    public Long requireUserId() {
        return requireUser().id();
    }

    public Optional<UserRole> currentRole() {
        return currentUser().map(AuthenticatedUser::role);
    }

    public boolean isAdmin() {
        return currentUser().map(AuthenticatedUser::isAdmin).orElse(false);
    }

    /** Municipalities the caller currently serves — empty for citizens. */
    public Set<Long> municipalityIds() {
        return currentUser().map(AuthenticatedUser::municipalityIds).orElseGet(Set::of);
    }

    /** Admins are cross-tenant by design (§8.2); everyone else needs a membership. */
    public boolean servesMunicipality(Long municipalityId) {
        return currentUser()
                .map(principal -> principal.isAdmin() || principal.servesMunicipality(municipalityId))
                .orElse(false);
    }

    /**
     * Tenancy guard for service methods (defense in depth behind {@code @PreAuthorize}).
     *
     * @throws AccessDeniedException if the caller has no current membership in the
     *         municipality; mapped to 403
     */
    public void requireMunicipality(Long municipalityId) {
        AuthenticatedUser principal = requireUser();
        if (principal.isAdmin() || principal.servesMunicipality(municipalityId)) {
            return;
        }
        throw new AccessDeniedException("No active membership in municipality " + municipalityId);
    }

    /** True when the caller is the owner of a resource, or an admin. */
    public boolean isOwnerOrAdmin(Long ownerUserId) {
        return currentUser()
                .map(principal -> principal.isAdmin() || principal.id().equals(ownerUserId))
                .orElse(false);
    }
}
