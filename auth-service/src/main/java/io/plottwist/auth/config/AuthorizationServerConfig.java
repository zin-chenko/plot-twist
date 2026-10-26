package io.plottwist.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.plottwist.auth.oauth.infrastructure.crypto.SigningKeys;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;

@Configuration
public class AuthorizationServerConfig {
  @Bean
  JWKSource<SecurityContext> signingKey(
      @Value("${auth.signing-key}") String path,
      @Value("${auth.auto-create-key:false}") boolean autoCreate)
      throws Exception {
    var keys = new JWKSet(SigningKeys.load(Path.of(path), autoCreate));
    return (selector, context) -> selector.select(keys);
  }

  @Bean
  JwtDecoder jwtDecoder(JWKSource<SecurityContext> source) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(source);
  }

  @Bean
  AuthorizationServerSettings settings(@Value("${auth.issuer}") String issuer) {
    return AuthorizationServerSettings.builder().issuer(issuer).build();
  }

  @Bean
  OAuth2TokenCustomizer<JwtEncodingContext> accessTokenClaims(JdbcTemplate jdbc) {
    return context -> {
      if (!AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
        var ids =
            jdbc.queryForList(
                "SELECT id FROM accounts WHERE username = ? AND verified = true",
                java.util.UUID.class,
                context.getPrincipal().getName());
        if (!ids.isEmpty()) {
          context.getClaims().subject(ids.getFirst().toString());
          context.getClaims().claim("account_id", ids.getFirst().toString());
        }
      }
      if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
        // Use the JDBC/Jackson-supported collection type for persisted token metadata.
        context
            .getClaims()
            .audience(new ArrayList<>(List.of("plot-twist-api", "plot-twist-domain")));
      }
    };
  }
}
