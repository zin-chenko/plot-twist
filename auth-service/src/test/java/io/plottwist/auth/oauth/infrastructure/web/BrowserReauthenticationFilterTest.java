package io.plottwist.auth.oauth.infrastructure.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.plottwist.auth.oauth.infrastructure.client.BrowserClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class BrowserReauthenticationFilterTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void forcesCredentialsOnceForExactRegisteredBrowserRedirect() throws Exception {
    var cache = new AuthorizationRequestCache();
    var clients = mock(RegisteredClientRepository.class);
    when(clients.findByClientId("plot-twist-browser"))
        .thenReturn(BrowserClientFactory.create("http://localhost:4200/auth/callback"));
    var filter = new BrowserReauthenticationFilter(cache, clients);
    var request = request("http://localhost:4200/auth/callback");
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("user", "", java.util.List.of()));
    SecurityContextHolder.setContext(context);
    request
        .getSession()
        .setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals("/login", response.getRedirectedUrl());
    assertNull(chain.getRequest());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertNull(
        request
            .getSession()
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    var saved = cache.getRequest(request, response);
    assertNotNull(saved);
    assertTrue(saved.getRedirectUrl().contains("state=original"));
    assertFalse(saved.getRedirectUrl().contains("prompt="));
    var continuation = new MockHttpServletRequest("GET", "/oauth2/authorize");
    continuation.setSession((MockHttpSession) request.getSession());
    var next = new MockFilterChain();
    filter.doFilter(continuation, new MockHttpServletResponse(), next);
    assertNotNull(next.getRequest());
  }

  @Test
  void invalidRedirectDoesNotClearAnAuthenticatedSession() throws Exception {
    var cache = new AuthorizationRequestCache();
    var clients = mock(RegisteredClientRepository.class);
    when(clients.findByClientId("plot-twist-browser"))
        .thenReturn(BrowserClientFactory.create("http://localhost:4200/auth/callback"));
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated("user", "", java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var request = request("https://unregistered.example/auth/callback");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    new BrowserReauthenticationFilter(cache, clients).doFilter(request, response, chain);
    assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
    assertNotNull(chain.getRequest());
    assertNull(response.getRedirectedUrl());
    assertNull(request.getSession(false));
  }

  private MockHttpServletRequest request(String redirect) {
    var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
    request.addParameter("client_id", "plot-twist-browser");
    request.addParameter("prompt", "login");
    request.addParameter("state", "original");
    request.addParameter("redirect_uri", redirect);
    return request;
  }
}
