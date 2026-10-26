package io.plottwist.auth;

import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;
    private final TransactionTemplate transactions;
    private final String from;

    EmailOutbox(JdbcTemplate jdbc, JavaMailSender sender, TransactionTemplate transactions,
                @Value("${auth.mail-from}") String from, MeterRegistry metrics) {
        this.jdbc = jdbc; this.sender = sender; this.transactions = transactions; this.from = from;
        metrics.gauge("auth.email.outbox.pending", this, queue -> queue.count("attempts < 10"));
        metrics.gauge("auth.email.outbox.dead", this, queue -> queue.count("attempts >= 10"));
    }

    private double count(String predicate) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE " + predicate, Long.class);
        return count == null ? 0 : count;
    }

    @Scheduled(fixedDelayString="${auth.email-poll-ms:2000}")
    public void deliver() {
        for (int i = 0; i < 10; i++) {
            Boolean sent = transactions.execute(status -> {
                var rows = jdbc.query("""
                    SELECT id, recipient, subject, body, attempts FROM email_outbox
                    WHERE attempts < 10 AND next_attempt_at <= now() AND created_at > now() - interval '30 minutes' ORDER BY next_attempt_at
                    FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (row, n) -> new Email(row.getObject("id", UUID.class), row.getString("recipient"),
                    row.getString("subject"), row.getString("body"), row.getInt("attempts")));
                if (rows.isEmpty()) return false;
                var email = rows.getFirst();
                var message = new SimpleMailMessage();
                message.setFrom(from); message.setTo(email.recipient());
                message.setSubject(email.subject()); message.setText(email.body());
                try {
                    sender.send(message);
                    jdbc.update("DELETE FROM email_outbox WHERE id = ?", email.id());
                } catch (org.springframework.mail.MailException unavailable) {
                    long seconds = Math.min(3600, 5L << Math.min(10, email.attempts()));
                    jdbc.update("UPDATE email_outbox SET attempts = attempts + 1, next_attempt_at = now() + (? * interval '1 second') WHERE id = ?", seconds, email.id());
                }
                return true;
            });
            if (!Boolean.TRUE.equals(sent)) break;
        }
    }

    @Scheduled(fixedDelay=3600000)
    public void expire() {
        jdbc.update("DELETE FROM account_tokens WHERE expires_at < now()");
        jdbc.update("DELETE FROM email_outbox WHERE created_at < now() - interval '30 minutes'");
    }

    private record Email(UUID id, String recipient, String subject, String body, int attempts) {
        @Override public String toString() { return "Email[redacted]"; }
    }
}
