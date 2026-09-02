package com.nagorikseba.identity.api;

import com.nagorikseba.identity.api.dto.AuthResponse;
import com.nagorikseba.identity.api.dto.LoginRequest;
import com.nagorikseba.identity.api.dto.LogoutRequest;
import com.nagorikseba.identity.api.dto.RefreshRequest;
import com.nagorikseba.identity.api.dto.RegistrationRequest;
import com.nagorikseba.identity.service.AuthService;
import com.nagorikseba.identity.service.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Blueprint I10 — the authentication API (§8.2: all four endpoints are public).
 *
 * <p>Refresh tokens travel in the JSON body rather than a cookie: the {@code fetch}
 * client keeps them in storage it controls, and {@code /api/**} is stateless with CSRF
 * disabled, so a cookie-borne credential would have no CSRF protection.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** Creates a citizen account and returns a usable token pair. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegistrationRequest request,
                                                 HttpServletRequest httpRequest) {
        AuthResponse response = authService.register(request, ClientInfo.from(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Authenticates by email or phone. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request, ClientInfo.from(httpRequest)));
    }

    /**
     * Rotates a refresh token. The response carries a <em>new</em> refresh token; the one
     * sent in is dead from here on, and replaying it revokes the whole family (R9).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken(), ClientInfo.from(httpRequest)));
    }

    /** Revokes the supplied refresh token and its family. Always 204, even for junk. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
