package io.plottwist.auth.oauth.infrastructure.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationCleanup {
  private final JdbcTemplate jdbc;

  AuthorizationCleanup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Scheduled(fixedDelay = 60000)
  public void expire() {
    for (int i = 0; i < 10; i++) {
      int deleted =
          jdbc.update(
              """
              DELETE FROM oauth2_authorization WHERE id IN (
                  SELECT id FROM oauth2_authorization WHERE created_at < now() - interval '1 day'
                  AND coalesce(greatest(authorization_code_expires_at, access_token_expires_at,
                      refresh_token_expires_at, oidc_id_token_expires_at, user_code_expires_at,
                      device_code_expires_at), created_at) < now() - interval '1 day'
                  ORDER BY created_at LIMIT 1000 FOR UPDATE SKIP LOCKED
              )
              """);
      if (deleted < 1000) break;
    }
  }
}
