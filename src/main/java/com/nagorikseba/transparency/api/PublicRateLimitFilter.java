package com.nagorikseba.transparency.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public-API rate limit (§8.4): 60 requests/minute/IP on {@code /api/public/**}.
 *
 * <p>In-memory sliding window (single instance; the Bucket4j+Redis upgrade is a
 * Phase 6 concern). Exceeded callers get 429 with a {@code Retry-After} header
 * and a problem-shaped body. Only public paths are counted — authenticated
 * traffic is untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicRateLimitFilter extends OncePerRequestFilter {

    /** Allowed requests per IP per minute on public endpoints. */
    public static final int LIMIT_PER_MINUTE = 60;

    private static final long WINDOW_MILLIS = 60_000L;

    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public PublicRateLimitFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = clientIp(request);
        long now = clock.millis();
        Deque<Long> window = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        boolean allowed;
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            allowed = window.size() < LIMIT_PER_MINUTE;
            if (allowed) {
                window.addLast(now);
            }
        }
        if (!allowed) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"type\":\"urn:nagorik-seba:problem:rate-limited\","
                            + "\"title\":\"Too Many Requests\",\"status\":429,"
                            + "\"detail\":\"Public API limit is 60 requests per minute per IP\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    /** For tests: forget all recorded hits. */
    void reset() {
        hits.clear();
    }
}
