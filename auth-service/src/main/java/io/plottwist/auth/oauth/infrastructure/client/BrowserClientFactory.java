package io.plottwist.auth.oauth.infrastructure.client;

import java.time.Duration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;

public final class BrowserClientFactory {
  private BrowserClientFactory() {}

  public static RegisteredClient create(String redirectUri) {
    var uri = java.net.URI.create(redirectUri);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException(
          "Browser redirect must be an absolute HTTP(S) URL without query or fragment");
    var client =
        RegisteredClient.withId("plot-twist-browser")
            .clientId("plot-twist-browser")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirectUri)
            .postLogoutRedirectUri(uri.resolve("/signed-out").toString())
            .scope("openid")
            .scope("api.read")
            .scope("api.write")
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(false)
                    .build())
            .tokenSettings(
                TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build());
    // Two exact loopback origins for local development; never wildcard production redirects.
    String aliasHost =
        "localhost".equalsIgnoreCase(uri.getHost())
            ? "127.0.0.1"
            : "127.0.0.1".equals(uri.getHost()) ? "localhost" : null;
    if ("http".equals(uri.getScheme()) && aliasHost != null) {
      var alias =
          java.net.URI.create(
              "http://"
                  + aliasHost
                  + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                  + uri.getRawPath());
      client
          .redirectUri(alias.toString())
          .postLogoutRedirectUri(alias.resolve("/signed-out").toString());
    }
    return client.build();
  }
}
