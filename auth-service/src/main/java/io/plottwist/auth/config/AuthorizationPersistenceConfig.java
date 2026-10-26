package io.plottwist.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;

@Configuration
public class AuthorizationPersistenceConfig {
  @Bean
  RegisteredClientRepository clients(JdbcTemplate jdbc) {
    return new JdbcRegisteredClientRepository(jdbc);
  }

  @Bean
  OAuth2AuthorizationService authorizations(JdbcTemplate jdbc, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationService(jdbc, clients) {
      @Override
      public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        if (type != null && "code".equals(type.getValue())) {
          if (!org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive())
            throw new IllegalStateException("Authorization code redemption requires a transaction");
          jdbc.queryForList(
              "SELECT id FROM oauth2_authorization WHERE authorization_code_value = ? FOR UPDATE",
              String.class,
              token);
        }
        return super.findByToken(token, type);
      }
    };
  }

  @Bean
  OAuth2AuthorizationConsentService consents(
      JdbcTemplate jdbc, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
  }
}
