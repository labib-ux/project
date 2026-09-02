package com.nagorikseba.identity.service;

import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.api.dto.AuthResponse;
import com.nagorikseba.identity.api.dto.LoginRequest;
import com.nagorikseba.identity.api.dto.RegistrationRequest;
import com.nagorikseba.identity.api.dto.UserResponse;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.UserRepository;
import com.nagorikseba.identity.service.TokenService.TokenPair;
import com.nagorikseba.shared.config.JwtProperties;
import com.nagorikseba.shared.exception.AccountLockedException;
import com.nagorikseba.shared.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Blueprint I7 — registration and login (§8.1).
 *
 * <p>Registration normalizes both identifiers before the uniqueness check so
 * {@code +8801711112222} and {@code 01711112222} cannot become two accounts, and hashes
 * the password with BCrypt strength 12 (configured on the {@code PasswordEncoder} bean).
 *
 * <p>Login is deliberately <em>not</em> transactional: the lockout counter must survive
 * the exception that reports the failure, so {@link LoginAttemptTracker} commits it in a
 * separate transaction.
 *
 * <p>Passwords are never logged, and no log line distinguishes "unknown account" from
 * "wrong password".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email/phone or password";

    private final UserRepository userRepository;
    private final AppUserDetailsService appUserDetailsService;
    private final LoginAttemptTracker loginAttemptTracker;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    /**
     * Creates a CITIZEN account and logs it straight in.
     *
     * @throws ConflictException if the email or phone is already registered
     */
    @Transactional
    public AuthResponse register(RegistrationRequest request, ClientInfo clientInfo) {
        String email = IdentifierNormalizer.normalizeEmail(request.email());
        String phone = IdentifierNormalizer.normalizePhone(request.phone());

        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new ConflictException("An account with this phone number already exists");
        }

        User user = User.builder()
                .fullName(request.fullName().trim())
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.CITIZEN)
                .active(true)
                .build();

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent registration of the same identifier.
            throw new ConflictException("An account with these details already exists");
        }

        log.info("Registered new citizen account id={}", user.getId());
        return response(tokenService.issue(user, clientInfo));
    }

    /**
     * Authenticates by email or phone.
     *
     * <p>Order of checks is security-relevant: lockout is reported before the password is
     * even compared (so a locked account cannot be brute-forced further), and the
     * "account disabled" answer only appears <em>after</em> a correct password, so the
     * endpoint cannot be used to enumerate deactivated accounts.
     *
     * @throws BadCredentialsException for unknown identifiers and wrong passwords alike
     * @throws AccountLockedException  while a lock is in force
     * @throws DisabledException       when the credentials are right but the account is off
     */
    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        User user = appUserDetailsService.findByIdentifier(request.identifier())
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

        Instant now = clock.instant();
        if (user.isLocked(now)) {
            throw lockedException(user.getLockedUntil(), now);
        }

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            LoginAttemptTracker.LockState state = loginAttemptTracker.recordFailure(user.getId());
            if (state.lockedNow()) {
                log.warn("Account {} locked after too many failed login attempts", user.getId());
                throw lockedException(state.lockedUntil(), now);
            }
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        if (!user.isActive()) {
            throw new DisabledException("This account has been deactivated");
        }

        loginAttemptTracker.recordSuccess(user.getId());
        return response(tokenService.issue(user, clientInfo));
    }

    /** Exchanges a refresh token for a new pair (rotation + reuse detection live in I8). */
    public AuthResponse refresh(String rawRefreshToken, ClientInfo clientInfo) {
        return response(tokenService.rotate(rawRefreshToken, clientInfo));
    }

    /** Revokes the presented refresh token's family. Idempotent. */
    public void logout(String rawRefreshToken) {
        tokenService.revoke(rawRefreshToken);
    }

    private AccountLockedException lockedException(Instant lockedUntil, Instant now) {
        long seconds = lockedUntil == null ? 0 : Math.max(Duration.between(now, lockedUntil).toSeconds(), 1);
        return new AccountLockedException(
                "Too many failed login attempts. Try again in " + Math.max(seconds / 60, 1) + " minute(s).",
                seconds);
    }

    private AuthResponse response(TokenPair pair) {
        return new AuthResponse(
                pair.accessToken(),
                AuthResponse.BEARER,
                jwtProperties.getAccessTokenSeconds(),
                pair.refreshToken(),
                toUserResponse(pair.user(), pair.municipalityIds()));
    }

    private UserResponse toUserResponse(User user, Set<Long> municipalityIds) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                municipalityIds);
    }
}
