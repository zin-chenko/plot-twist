package io.plottwist.auth.oauth.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/** Only an interactive authorization request may determine where form login resumes. */
public class AuthorizationRequestCache extends HttpSessionRequestCache {
  public AuthorizationRequestCache() {
    setRequestMatcher(
        request ->
            "GET".equals(request.getMethod())
                && request.getRequestURI().equals(request.getContextPath() + "/oauth2/authorize"));
  }

  @Override
  public SavedRequest getRequest(HttpServletRequest request, HttpServletResponse response) {
    SavedRequest saved = super.getRequest(request, response);
    if (saved != null && !isAuthorization(saved, request.getContextPath())) {
      // Also discard unrelated requests saved by versions deployed before this restriction.
      super.removeRequest(request, response);
      return null;
    }
    return saved;
  }

  private static boolean isAuthorization(SavedRequest saved, String contextPath) {
    try {
      return "GET".equals(saved.getMethod())
          && (contextPath + "/oauth2/authorize")
              .equals(URI.create(saved.getRedirectUrl()).getPath());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
