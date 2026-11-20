package com.demo.upimesh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory per-client limit for authenticated API writes in this demo instance. */
public final class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int requestsPerMinute;
    private final boolean writesEnabled;
    private final ConcurrentHashMap<String, Window> clients = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(int requestsPerMinute, boolean writesEnabled) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("Rate limit must be positive");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.writesEnabled = writesEnabled;
    }

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
        if (!writesEnabled) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"demo_writes_disabled\"}");
            return;
        }

        String client = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (allow(client, System.currentTimeMillis())) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
    }

    private boolean allow(String client, long now) {
        if (clients.size() >= MAX_TRACKED_CLIENTS) {
            clients.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
            if (clients.size() >= MAX_TRACKED_CLIENTS && !clients.containsKey(client)) {
                return false;
            }
        }

        AtomicBoolean allowed = new AtomicBoolean();
        clients.compute(client, (key, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_MILLIS) {
                allowed.set(true);
                return new Window(now, 1);
            }
            if (current.count < requestsPerMinute) {
                allowed.set(true);
                return new Window(current.startedAt, current.count + 1);
            }
            return current;
        });
        return allowed.get();
    }

    private record Window(long startedAt, int count) {}
}
