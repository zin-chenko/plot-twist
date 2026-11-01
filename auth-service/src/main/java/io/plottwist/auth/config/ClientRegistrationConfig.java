package io.plottwist.auth.config;

import io.plottwist.auth.oauth.infrastructure.client.BrowserClientFactory;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.provisioning.JdbcUserDetailsManager;

@Configuration
public class ClientRegistrationConfig {
  @Bean
  ApplicationRunner bootstrap(
      JdbcUserDetailsManager users,
      RegisteredClientRepository clients,
      PasswordEncoder encoder,
      @Value("${auth.bootstrap.username:}") String username,
      @Value("${auth.bootstrap.password:}") String password,
      @Value("${auth.client-secret:}") String clientSecret,
      @Value("${auth.bootstrap.enabled:false}") boolean bootstrapEnabled,
      @Value("${auth.machine-client.enabled:false}") boolean machineEnabled,
      @Value("${auth.redirect-uri}") String redirectUri,
      @Value("${auth.browser-redirect-uri:}") String browserRedirectUri,
      @Value("${auth.browser-session-duration:4h}") Duration browserSessionDuration) {
    return args -> {
      if (bootstrapEnabled && !users.userExists(username)) {
        users.createUser(
            User.withUsername(username).password(encoder.encode(password)).roles("USER").build());
      }
      // Client configuration is reconciled on startup; users are only bootstrapped once.
      if (machineEnabled)
        clients.save(
            RegisteredClient.withId("plot-twist-cli")
                .clientId("plot-twist-cli")
                .clientSecret(encoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("api.read")
                .tokenSettings(
                    TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
                .build());
      clients.save(
          RegisteredClient.withId("plot-twist-web")
              .clientId("plot-twist-web")
              .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri(redirectUri)
              .scope("openid")
              .scope("api.read")
              .scope("api.write")
              .clientSettings(
                  ClientSettings.builder()
                      .requireProofKey(true)
                      .requireAuthorizationConsent(false)
                      .build())
              .tokenSettings(
                  TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
              .build());
      if (!browserRedirectUri.isBlank())
        clients.save(BrowserClientFactory.create(browserRedirectUri, browserSessionDuration));
    };
  }
}
