package io.plottwist.auth.oauth.infrastructure.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

class AuthorizationRequestCacheTest {
  private final AuthorizationRequestCache cache = new AuthorizationRequestCache();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void backgroundRequestsCannotReplaceAuthorizationAndItsPkceParameters() {
    var session = new MockHttpSession();
    var authorize = request("GET", "/oauth2/authorize", session);
    authorize.setQueryString(
        "client_id=browser&state=original&code_challenge=pkce&code_challenge_method=S256");
    cache.saveRequest(authorize, response);
    String redirect = cache.getRequest(authorize, response).getRedirectUrl();
    assertTrue(redirect.contains("state=original"));
    assertTrue(redirect.contains("code_challenge=pkce"));
    for (String path :
        new String[] {
          "/.well-known/appspecific/com.chrome.devtools.json", "/favicon.ico", "/oauth2/missing"
        }) {
      var background = request("GET", path, session);
      cache.saveRequest(background, response);
      assertEquals(redirect, cache.getRequest(background, response).getRedirectUrl());
    }
  }

  @Test
  void unrelatedRequestsDoNotCreateSessionsAndPostIsNotSaved() {
    for (String path :
        new String[] {
          "/.well-known/appspecific/com.chrome.devtools.json",
          "/login",
          "/error",
          "/oauth2/authorize/extra"
        }) {
      var request = new MockHttpServletRequest("GET", path);
      cache.saveRequest(request, response);
      assertNull(request.getSession(false));
    }
    var post = new MockHttpServletRequest("POST", "/oauth2/authorize");
    cache.saveRequest(post, response);
    assertNull(post.getSession(false));
  }

  @Test
  void removesUnrelatedRequestsLeftInAnExistingSessionByPreviousVersions() {
    var request =
        request("GET", "/.well-known/appspecific/com.chrome.devtools.json", new MockHttpSession());
    var oldCache = new HttpSessionRequestCache();
    oldCache.saveRequest(request, response);
    assertNotNull(oldCache.getRequest(request, response));
    assertNull(cache.getRequest(request, response));
    assertNull(oldCache.getRequest(request, response));
  }

  @Test
  void removesLoginPromptFromSavedContinuationButPreservesOAuthStateAndPkce() {
    var request = request("GET", "/oauth2/authorize", new MockHttpSession());
    request.addParameter("prompt", "login");
    request.addParameter("state", "original");
    request.addParameter("code_challenge", "challenge");
    request.addParameter("redirect_uri", "http://localhost:4200/auth/callback");
    request.setQueryString(
        "prompt=login&state=original&code_challenge=challenge&redirect_uri=http%3A%2F%2Flocalhost%3A4200%2Fauth%2Fcallback");
    cache.saveRequest(request, response);
    var saved = cache.getRequest(request, response);
    assertFalse(saved.getRedirectUrl().contains("prompt="));
    assertFalse(saved.getParameterMap().containsKey("prompt"));
    assertEquals("original", saved.getParameterMap().get("state")[0]);
    assertTrue(saved.getRedirectUrl().contains("code_challenge=challenge"));
  }

  @Test
  void supportsServletContextPaths() {
    var request = request("GET", "/auth/oauth2/authorize", new MockHttpSession());
    request.setContextPath("/auth");
    cache.saveRequest(request, response);
    assertNotNull(cache.getRequest(request, response));
  }

  private MockHttpServletRequest request(String method, String path, MockHttpSession session) {
    var request = new MockHttpServletRequest(method, path);
    request.setSession(session);
    return request;
  }
}
