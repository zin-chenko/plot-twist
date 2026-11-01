package io.plottwist.auth.oauth.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
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
  public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
    if ("login".equals(request.getParameter("prompt"))) {
      // A completed credential login must resume OAuth once, without forcing another login.
      var params = new LinkedHashMap<>(request.getParameterMap());
      params.remove("prompt");
      String query =
          params.entrySet().stream()
              .flatMap(
                  entry ->
                      Arrays.stream(entry.getValue())
                          .map(
                              value ->
                                  URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                                      + "="
                                      + URLEncoder.encode(value, StandardCharsets.UTF_8)))
              .collect(Collectors.joining("&"));
      super.saveRequest(
          new HttpServletRequestWrapper(request) {
            @Override
            public String getQueryString() {
              return query;
            }

            @Override
            public Map<String, String[]> getParameterMap() {
              return params;
            }
          },
          response);
    } else super.saveRequest(request, response);
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
