package io.plottwist.auth.oauth.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/** SAS 1.5 does not enforce prompt=login; only our exact registered browser redirects opt in. */
public class BrowserReauthenticationFilter extends OncePerRequestFilter {
  private final RequestCache cache;
  private final RegisteredClientRepository clients;

  public BrowserReauthenticationFilter(RequestCache cache, RegisteredClientRepository clients) {
    this.cache = cache;
    this.clients = clients;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("GET".equals(request.getMethod())
        && request.getRequestURI().equals(request.getContextPath() + "/oauth2/authorize")
        && "login".equals(request.getParameter("prompt"))
        && "plot-twist-browser".equals(request.getParameter("client_id"))) {
      var client = clients.findByClientId("plot-twist-browser");
      // Unknown clients/redirects continue to the existing OAuth validator, without ending a
      // session.
      if (client != null
          && client.getRedirectUris().contains(request.getParameter("redirect_uri"))) {
        cache.saveRequest(request, response);
        var empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        new HttpSessionSecurityContextRepository().saveContext(empty, request, response);
        response.sendRedirect(request.getContextPath() + "/login");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
