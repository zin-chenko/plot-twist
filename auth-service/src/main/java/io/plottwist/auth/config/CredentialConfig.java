package io.plottwist.auth.config;

import io.plottwist.auth.account.model.EmailAddress;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class CredentialConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new org.springframework.security.crypto.password.DelegatingPasswordEncoder(
        "argon2",
        java.util.Map.of(
            "argon2",
                new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
                    16, 32, 1, 19456, 2),
            "bcrypt", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()));
  }

  @Bean
  JdbcUserDetailsManager users(DataSource source, JdbcTemplate jdbc) {
    return new JdbcUserDetailsManager(source) {
      @Override
      public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(
          String username) {
        var details = super.loadUserByUsername(EmailAddress.normalize(username));
        Integer locked =
            jdbc.queryForObject(
                "SELECT count(*) FROM login_attempts WHERE username = ? AND locked_until > now()",
                Integer.class,
                details.getUsername());
        return User.withUserDetails(details).accountLocked(locked != null && locked > 0).build();
      }
    };
  }
}
