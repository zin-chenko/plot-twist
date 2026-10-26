package io.plottwist.auth;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthWorkLimiter extends OncePerRequestFilter {
    private final Semaphore slots = new Semaphore(8);
    private static final Set<String> PATHS = Set.of("/login", "/oauth2/token", "/auth/v1/registrations", "/auth/v1/password-resets");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!request.getMethod().equals("POST") || !PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (!slots.tryAcquire()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429}");
            return;
        }
        try { chain.doFilter(request, response); } finally { slots.release(); }
    }
}
