package com.nagorikseba.service;

import com.nagorikseba.dto.auth.AuthResponse;
import com.nagorikseba.dto.auth.LoginRequest;
import com.nagorikseba.dto.auth.RegistrationRequest;
import com.nagorikseba.dto.auth.UserResponse;
import com.nagorikseba.entity.User;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.shared.exception.ConflictException;
import com.nagorikseba.repository.UserRepository;
import com.nagorikseba.security.JwtTokenProvider;
import com.nagorikseba.shared.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    @Transactional
    public AuthResponse register(RegistrationRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = normalizePhone(request.phone());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new ConflictException("An account with this phone number already exists");
        }

        User citizen = userRepository.save(User.builder()
                .fullName(request.fullName().trim())
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(request.password()))
                .role(UserRole.CITIZEN)
                .active(true)
                .build());
        return tokenResponse(citizen);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String identifier = normalizeIdentifier(request.identifier());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(identifier, request.password()));
        } catch (AuthenticationException exception) {
            throw new BadCredentialsException("Invalid email/phone or password");
        }
        User user = userRepository.findByEmailIgnoreCase(identifier)
                .or(() -> userRepository.findByPhone(identifier))
                .orElseThrow(() -> new BadCredentialsException("Invalid email/phone or password"));
        return tokenResponse(user);
    }

    private AuthResponse tokenResponse(User user) {
        return new AuthResponse(
                jwtTokenProvider.createAccessToken(user),
                "Bearer",
                jwtProperties.getExpirationSeconds(),
                new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole()));
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String trimmed = phone.trim();
        return trimmed.startsWith("+880") ? "0" + trimmed.substring(4) : trimmed;
    }

    private String normalizeIdentifier(String identifier) {
        String trimmed = identifier.trim();
        return trimmed.startsWith("+880") ? "0" + trimmed.substring(4) : trimmed.toLowerCase(Locale.ROOT);
    }
}
