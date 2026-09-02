package com.nagorikseba.identity.service;

import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.domain.UserMunicipalityMembership;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.shared.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Blueprint I9 — loads a user by email or phone together with the memberships that
 * become the {@code mids} token claim.
 *
 * <p>Also the application's {@link UserDetailsService}, which keeps Boot from
 * auto-configuring an in-memory user. The login flow itself does not go through
 * {@code DaoAuthenticationProvider}: lockout bookkeeping needs the {@link User}
 * entity, so {@link AuthService} performs the password check directly.
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = findByIdentifier(identifier)
                .orElseThrow(() -> new UsernameNotFoundException("No account for the given identifier"));
        return toPrincipal(user);
    }

    /**
     * Looks up a user by email or phone in canonical form.
     *
     * <p>Returns empty rather than throwing so callers can produce one uniform
     * "invalid credentials" answer for unknown accounts and wrong passwords alike.
     */
    @Transactional(readOnly = true)
    public Optional<User> findByIdentifier(String rawIdentifier) {
        String identifier = IdentifierNormalizer.normalizeIdentifier(rawIdentifier);
        if (identifier == null) {
            return Optional.empty();
        }
        return userRepository.findByIdentifier(identifier);
    }

    /** Municipalities the user currently serves (current = {@code valid_until IS NULL}). */
    @Transactional(readOnly = true)
    public Set<Long> currentMunicipalityIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (UserMunicipalityMembership membership : membershipRepository.findByUserIdAndValidUntilIsNull(userId)) {
            ids.add(membership.getMunicipality().getId());
        }
        return ids;
    }

    /** Builds the token principal for an already-loaded user. */
    @Transactional(readOnly = true)
    public AuthenticatedUser toPrincipal(User user) {
        return new AuthenticatedUser(user.getId(), user.canonicalIdentifier(), user.getRole(),
                currentMunicipalityIds(user.getId()));
    }
}
