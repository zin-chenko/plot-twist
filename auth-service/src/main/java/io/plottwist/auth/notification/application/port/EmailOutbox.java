package io.plottwist.auth.notification.application.port;

/** Enqueue within the caller's database transaction; delivery is asynchronous. */
public interface EmailOutbox {
  void enqueue(String recipient, String subject, String body);
}
