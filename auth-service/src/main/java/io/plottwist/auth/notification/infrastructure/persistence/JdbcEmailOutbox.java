package io.plottwist.auth.notification.infrastructure.persistence;

import io.plottwist.auth.notification.application.port.EmailOutbox;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmailOutbox implements EmailOutbox {
  private final JdbcTemplate jdbc;

  public JdbcEmailOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void enqueue(String recipient, String subject, String body) {
    jdbc.update(
        "INSERT INTO email_outbox(id, recipient, subject, body) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        recipient,
        subject,
        body);
  }
}
