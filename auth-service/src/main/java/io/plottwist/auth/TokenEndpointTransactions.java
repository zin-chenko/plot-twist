package io.plottwist.auth;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class TokenEndpointTransactions extends OncePerRequestFilter {
    private final TransactionTemplate transactions;
    TokenEndpointTransactions(TransactionTemplate transactions) { this.transactions = transactions; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!request.getMethod().equals("POST") || !request.getRequestURI().equals("/oauth2/token")) {
            chain.doFilter(request, response);
            return;
        }
        var buffered = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status -> {
                try { chain.doFilter(request, buffered); }
                catch (IOException | ServletException error) { throw new FilterFailure(error); }
            });
            // Never expose an issued JWT before the corresponding DB transaction has committed.
            buffered.copyBodyToResponse();
        } catch (FilterFailure failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw (ServletException) failed.getCause();
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            response.resetBuffer();
            response.setStatus(503);
            response.setContentType("application/problem+json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}");
        }
    }
    private static class FilterFailure extends RuntimeException {
        FilterFailure(Exception error) { super(error); }
    }
}
