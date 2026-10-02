package com.nagorikseba.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT authentication for the Thymeleaf page chain.
 *
 * <p>Browser navigations cannot send {@code Authorization} headers, so the
 * API-only Bearer filter leaves every page anonymous — including
 * {@code /admin/**}, whose ADMIN rule then 403s even for admins holding a
 * valid token. This filter accepts the same access token from three carriers,
 * in order: the {@code Authorization} header, the {@code nagorikSebaToken}
 * cookie written by {@code auth.js} on login, and the {@code ?accessToken=}
 * query parameter (for opening an admin page in a fresh tab).
 *
 * <p>Like its API sibling it is deliberately not a Spring bean: it is
 * instantiated by {@code SecurityConfig} inside the page chain only, so it
 * never runs for {@code /api/**}. An invalid token leaves the context
 * anonymous and the page chain's rules decide (redirect/403) as before.
 */
@RequiredArgsConstructor
public class WebJwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "nagorikSebaToken";
    public static final String QUERY_PARAM = "accessToken";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearer(request);
            if (token == null) {
                token = cookie(request);
            }
            if (token == null) {
                token = queryParam(request);
            }
            if (token != null && !token.isBlank()) {
                jwtTokenProvider.parseAccessToken(token.trim())
                        .ifPresent(principal -> authenticate(principal, request));
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        return header != null && header.startsWith(PREFIX)
                ? header.substring(PREFIX.length()) : null;
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String queryParam(HttpServletRequest request) {
        return request.getParameter(QUERY_PARAM);
    }

    private void authenticate(AuthenticatedUser principal, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
