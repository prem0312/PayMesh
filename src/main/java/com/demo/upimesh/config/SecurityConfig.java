package com.demo.upimesh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${paymesh.api-write-limit-per-minute:60}")
    private int apiWriteLimitPerMinute;

    @Value("${paymesh.demo.writes-enabled:true}")
    private boolean demoWritesEnabled;

    @Value("${paymesh.demo.public-dashboard:false}")
    private boolean publicDashboard;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // The dashboard uses same-origin JSON fetch calls and cannot send a CSRF token.
                // ApiWriteOriginFilter below rejects cross-origin browser writes instead.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .httpBasic(basic -> {})
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/health").permitAll();
                    if (publicDashboard) {
                        auth.requestMatchers(HttpMethod.GET, "/", "/api/demo/dashboard").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterAfter(new ApiWriteOriginFilter(), AuthorizationFilter.class)
                .addFilterAfter(new ApiRateLimitFilter(apiWriteLimitPerMinute, demoWritesEnabled && !publicDashboard),
                        ApiWriteOriginFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService demoUserDetailsService(
            @Value("${paymesh.demo.username:}") String username,
            @Value("${paymesh.demo.password:}") String password,
            PasswordEncoder passwordEncoder) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "Set PAYMESH_DEMO_USERNAME and PAYMESH_DEMO_PASSWORD before starting PayMesh");
        }

        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("DEMO")
                .build());
    }

    /** Require same-origin browser writes while leaving the dashboard HTML unchanged. */
    private static final class ApiWriteOriginFilter extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String method = request.getMethod();
            return !request.getRequestURI().startsWith(request.getContextPath() + "/api/")
                    || !("POST".equals(method) || "PUT".equals(method)
                    || "PATCH".equals(method) || "DELETE".equals(method));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String originHeader = request.getHeader("Origin");
            if (originHeader == null || !isSameOrigin(originHeader, request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Same-origin API request required");
                return;
            }
            filterChain.doFilter(request, response);
        }

        private boolean isSameOrigin(String originHeader, HttpServletRequest request) {
            try {
                URI origin = new URI(originHeader);
                if (origin.getHost() == null || origin.getUserInfo() != null
                        || origin.getPath() != null && !origin.getPath().isEmpty()
                        || origin.getQuery() != null || origin.getFragment() != null) {
                    return false;
                }

                String forwardedProto = request.getHeader("X-Forwarded-Proto");
                String requestScheme = forwardedProto == null
                        ? request.getScheme() : forwardedProto.split(",", 2)[0].trim();
                String forwardedHost = request.getHeader("X-Forwarded-Host");
                String requestHost = forwardedHost == null
                        ? request.getServerName() : forwardedHost.split(",", 2)[0].trim();
                int requestPort = request.getServerPort();
                if (forwardedHost != null) {
                    URI hostUri = new URI(requestScheme + "://" + requestHost);
                    requestHost = hostUri.getHost();
                    requestPort = hostUri.getPort() == -1 ? defaultPort(requestScheme) : hostUri.getPort();
                }

                int originPort = origin.getPort() == -1 ? defaultPort(origin.getScheme()) : origin.getPort();
                int effectiveRequestPort = requestPort == -1 ? defaultPort(requestScheme) : requestPort;
                return origin.getScheme() != null
                        && origin.getScheme().equalsIgnoreCase(requestScheme)
                        && origin.getHost().equalsIgnoreCase(requestHost)
                        && originPort == effectiveRequestPort;
            } catch (URISyntaxException | IllegalArgumentException ex) {
                return false;
            }
        }

        private int defaultPort(String scheme) {
            if ("https".equalsIgnoreCase(scheme)) return 443;
            if ("http".equalsIgnoreCase(scheme)) return 80;
            return -1;
        }
    }
}
