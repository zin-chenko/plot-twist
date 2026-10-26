package io.plottwist.auth.oauth.infrastructure.security;

import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;

public final class BrowserRedirectValidator {
  private BrowserRedirectValidator() {}

  public static void validate(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    if ("plot-twist-browser".equals(context.getRegisteredClient().getClientId())) {
      OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
      if (!context.getRegisteredClient().getRedirectUris().contains(request.getRedirectUri()))
        // No authentication result: the error handler must never redirect to an untrusted URI.
        throw new OAuth2AuthorizationCodeRequestAuthenticationException(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, "Invalid redirect_uri", null), null);
    }
  }
}
