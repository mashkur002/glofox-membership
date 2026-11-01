package com.glofox.memberships.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless HTTP Basic authentication for the API.
 *
 * <p><strong>Two users, both from the environment.</strong> The four
 * {@code MEMBERSHIPS_*} variables have no defaults, so a missing one stops
 * startup instead of booting with a guessable credential. The passwords are
 * BCrypt-encoded once, here; only the hashes are kept.
 *
 * <p><strong>Both roles reach every endpoint.</strong> The roles exist so a
 * later change can split them, not to restrict anything today. A principal
 * holding neither role (not possible with these two users, but possible if the
 * user source ever changes) gets 403.
 *
 * <p><strong>Deny by default.</strong> {@code /v1/**} is the API; every other
 * path is denied, so a route added outside it is protected by accident rather
 * than exposed by accident. {@code /error} stays open because Spring forwards
 * there to render errors, and securing it would turn a 404 or 500 into a 401.
 *
 * <p><strong>Why CSRF is off.</strong> CSRF exploits a credential the browser
 * attaches by itself — a session cookie. There is no session and no cookie
 * here; the credential is an explicit header on every request, which a
 * cross-site form cannot set.
 *
 * <p><strong>Cost to know about.</strong> Basic is re-checked on every request,
 * and BCrypt is slow by design (tens of milliseconds at the default strength).
 * Acceptable for this service; at scale it would argue for a token.
 */
@Configuration
public class SecurityConfig {

    private static final String REALM = "memberships";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            PasswordEncoder encoder,
            @Value("${MEMBERSHIPS_ADMIN_USERNAME}") String adminUsername,
            @Value("${MEMBERSHIPS_ADMIN_PASSWORD}") String adminPassword,
            @Value("${MEMBERSHIPS_USER_USERNAME}") String userUsername,
            @Value("${MEMBERSHIPS_USER_PASSWORD}") String userPassword) {
        requireNonBlank("MEMBERSHIPS_ADMIN_USERNAME", adminUsername);
        requireNonBlank("MEMBERSHIPS_ADMIN_PASSWORD", adminPassword);
        requireNonBlank("MEMBERSHIPS_USER_USERNAME", userUsername);
        requireNonBlank("MEMBERSHIPS_USER_PASSWORD", userPassword);
        if (adminUsername.equals(userUsername)) {
            throw new IllegalStateException(
                    "MEMBERSHIPS_ADMIN_USERNAME and MEMBERSHIPS_USER_USERNAME must differ");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername)
                        .password(encoder.encode(adminPassword))
                        .roles("ADMIN")
                        .build(),
                User.withUsername(userUsername)
                        .password(encoder.encode(userPassword))
                        .roles("USER")
                        .build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"");
            // Fixed text: never says whether the user or the password was wrong.
            writeProblem(json, request, response, HttpStatus.UNAUTHORIZED, "unauthorized",
                         "Full authentication is required.");
        };
        AccessDeniedHandler forbidden = (request, response, ex) ->
                writeProblem(json, request, response, HttpStatus.FORBIDDEN, "forbidden",
                             "Access is denied.");

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.realmName(REALM).authenticationEntryPoint(unauthorized))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
                                         "/swagger-ui/**", "/redoc.html").permitAll()
                        .requestMatchers("/v1/**").hasAnyRole("ADMIN", "USER")
                        .anyRequest().denyAll());
        return http.build();
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set and non-blank");
        }
    }

    /**
     * The same RFC 9457 shape {@code ApiExceptionHandler} produces, so a client
     * has one error format to parse. Written here because these responses are
     * produced by the filter chain, before any controller advice can run.
     */
    private static void writeProblem(JsonMapper json, HttpServletRequest request,
                                     HttpServletResponse response, HttpStatus status,
                                     String code, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:glofox:problem:" + code);
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
