package com.nagorikseba.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet rate limits (§8.4): in-memory sliding-window buckets.
 *
 * <p>Rules (config-driven, defaults match the blueprint): login 5/min/IP,
 * register 3/hour/IP, complaint submit 10/day/caller (authenticated user id,
 * else IP). Only those three routes are counted — {@code /api/public/**} is
 * already covered by {@code PublicRateLimitFilter} and everything else passes
 * through untouched. Runs after the security chain (needs the principal for
 * per-user buckets). Exceeded callers get 429 with {@code Retry-After} and a
 * problem-shaped body.
 *
 * <p>Off unless {@code app.rate-limit.enabled=true} (production enables it;
 * suites enable it per-test with isolated client IPs).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int loginPerMinute;
    private final int registerPerHour;
    private final int submitPerDay;
    private final Clock clock;
    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.rate-limit.login-per-minute-per-ip:5}") int loginPerMinute,
            @Value("${app.rate-limit.register-per-hour-per-ip:3}") int registerPerHour,
            @Value("${app.rate-limit.complaint-submit-per-day-per-user:10}") int submitPerDay,
            Clock clock) {
        this.loginPerMinute = loginPerMinute;
        this.registerPerHour = registerPerHour;
        this.submitPerDay = submitPerDay;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !("/api/auth/login".equals(uri)
                || "/api/auth/register".equals(uri)
                || "/api/complaints".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        Rule rule;
        String identity;
        if ("/api/auth/login".equals(uri)) {
            rule = new Rule("login", loginPerMinute, 60_000L);
            identity = clientIp(request);
        } else if ("/api/auth/register".equals(uri)) {
            rule = new Rule("register", registerPerHour, 3_600_000L);
            identity = clientIp(request);
        } else {
            rule = new Rule("submit", submitPerDay, 86_400_000L);
            identity = callerIdentity(request);
        }
        long now = clock.millis();
        String key = rule.name() + "|" + identity;
        Deque<Long> window = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Long retryAfterSeconds;
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= rule.windowMillis()) {
                window.pollFirst();
            }
            if (window.size() < rule.max()) {
                window.addLast(now);
                retryAfterSeconds = null;
            } else {
                retryAfterSeconds = Math.max(1L, (window.peekFirst() + rule.windowMillis() - now + 999) / 1000);
            }
        }
        if (retryAfterSeconds != null) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write(
                    "{\"type\":\"urn:nagorik-seba:problem:rate-limited\","
                            + "\"title\":\"Too Many Requests\",\"status\":429,"
                            + "\"detail\":\"Rate limit exceeded for this endpoint\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String callerIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof com.nagorikseba.shared.security.AuthenticatedUser principal) {
            return "user-" + principal.id();
        }
        return "ip-" + clientIp(request);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private record Rule(String name, int max, long windowMillis) {
    }
}
