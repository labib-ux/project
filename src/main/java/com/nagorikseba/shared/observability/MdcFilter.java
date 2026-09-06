package com.nagorikseba.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Request log correlation (§10): every request gets a {@code traceId} for the
 * duration of its processing (cleared afterwards so pooled threads never leak
 * it). Tenant fields ({@code municipalityId}, {@code complaintId}) are added
 * by {@code MetricsAspect} around lifecycle transitions, where the entities —
 * rather than raw path strings — are available.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
