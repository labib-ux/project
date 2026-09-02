package com.nagorikseba.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.shared.exception.ApiError;
import com.nagorikseba.shared.security.JwtAuthenticationFilter;
import com.nagorikseba.shared.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Blueprint S3 — two independent filter chains (§8.3).
 *
 * <p><strong>Chain 1, {@code /api/**}:</strong> stateless. Authentication comes from a
 * bearer JWT, no session is created, and CSRF is disabled — legitimately, because there
 * is no ambient credential a cross-site form could ride on.
 *
 * <p><strong>Chain 2, everything else:</strong> the Thymeleaf pages. These <em>do</em>
 * use a cookie, so CSRF stays on with {@link CookieCsrfTokenRepository} plus Spring
 * Security 6's {@link XorCsrfTokenRequestAttributeHandler} (BREACH mitigation: the token
 * in the response body is masked per request).
 *
 * <p>Splitting the chains is what makes "CSRF off" safe. A single chain with CSRF
 * globally disabled would strip the protection from the HTML forms too.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** §8.1: BCrypt cost 12 — deliberately slow, tuned for 2025 hardware. */
    private static final int BCRYPT_STRENGTH = 12;

    /**
     * Self-hosted assets plus the two CDNs the map pages need (§8.3). Kept narrow: no
     * {@code unsafe-eval}, and {@code frame-ancestors 'none'} to match X-Frame-Options.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://unpkg.com",
            "style-src 'self' 'unsafe-inline' https://unpkg.com",
            "img-src 'self' data: blob: https://*.tile.openstreetmap.org https://unpkg.com",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Browser origins allowed to call the API. Defaults to the app's own origin only —
     * §8.3 forbids {@code *}, and a wildcard plus {@code allowCredentials} is rejected by
     * Spring anyway.
     */
    @Value("${app.security.cors.allowed-origins:http://localhost:8080}")
    private List<String> allowedOrigins;

    /** Stateless JSON API. Registered first so it claims {@code /api/**}. */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Safe here and only here: no cookie authenticates an /api/** call.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .headers(this::apiHeaders)
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight carries no credentials and must not 401.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/api/public/**").permitAll()
                        // Reference data: read-only, slug-addressed, no tenant data (§8.2).
                        .requestMatchers(HttpMethod.GET, "/api/municipalities/**").permitAll()
                        .requestMatchers("/api/authority/**")
                        .hasAnyRole("WARD_COUNCILOR", "DEPT_OFFICER", "ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/complaints/**").hasAnyRole("CITIZEN", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler((request, response, denied) -> writeForbidden(request, response)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Server-rendered pages: session cookie, CSRF on, redirect to /login when needed. */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .headers(this::pageHeaders)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/register", "/citizen/complaint/new").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/uploads/**", "/favicon.ico").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-XSRF-TOKEN"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** §8.3 headers for JSON responses: no framing, no sniffing, HSTS, no referrer leak. */
    private void apiHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.DISABLED))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                // A JSON response is never a document; the strictest policy is correct.
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"));
    }

    /** Same, plus the page CSP that has to allow the map assets. */
    private void pageHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer ->
                        referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.DISABLED))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY));
    }

    /**
     * 401 in the same {@code application/problem+json} shape the
     * {@code GlobalExceptionHandler} produces — the entry point runs in the filter chain,
     * before {@code @RestControllerAdvice} can see anything.
     */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException exception)
            throws IOException {
        writeProblem(request, response, HttpStatus.UNAUTHORIZED, "unauthorized",
                "Authentication required. Send a valid Bearer access token.");
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(request, response, HttpStatus.FORBIDDEN, "access-denied",
                "You do not have permission to perform this action.");
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                              String problemType, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(status, problemType, detail, request.getRequestURI(), null, clock.instant());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
