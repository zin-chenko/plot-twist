package io.plottwist.auth;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
public class LoginProtection {
    private final JdbcTemplate jdbc;
    LoginProtection(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener
    public void failed(AuthenticationFailureBadCredentialsEvent event) {
        String username = AccountService.email(event.getAuthentication().getName());
        jdbc.update("""
            INSERT INTO login_attempts(username, failures)
            SELECT username, 1 FROM users WHERE username = ?
            ON CONFLICT (username) DO UPDATE SET
                failures = CASE WHEN login_attempts.window_start < now() - interval '5 minutes' THEN 1 ELSE login_attempts.failures + 1 END,
                locked_until = CASE WHEN login_attempts.window_start >= now() - interval '5 minutes' AND login_attempts.failures >= 4 THEN now() + interval '5 minutes' ELSE null END,
                window_start = CASE WHEN login_attempts.window_start < now() - interval '5 minutes' THEN now() ELSE login_attempts.window_start END
            """, username);
    }

    @EventListener
    public void succeeded(AuthenticationSuccessEvent event) {
        jdbc.update("DELETE FROM login_attempts WHERE username = ?", AccountService.email(event.getAuthentication().getName()));
    }
}
