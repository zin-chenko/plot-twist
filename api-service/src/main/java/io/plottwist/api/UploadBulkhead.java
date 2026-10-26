package io.plottwist.api;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(100)
public class UploadBulkhead extends OncePerRequestFilter {
    private final Semaphore slots = new Semaphore(4);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        boolean upload = request.getMethod().equals("PUT") && request.getRequestURI().equals("/api/v1/profile/photo");
        if (!upload) { chain.doFilter(request, response); return; }
        if (!slots.tryAcquire()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Upload capacity reached; retry later\"}");
            return;
        }
        try { chain.doFilter(request, response); } finally { slots.release(); }
    }
}
