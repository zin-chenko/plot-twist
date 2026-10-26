package io.plottwist.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    AccountService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    static String email(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    static boolean validPassword(String password) {
        if (password == null || password.isBlank()) return false;
        int length = password.codePointCount(0, password.length());
        return length >= 15 && length <= 128 && password.codePoints().noneMatch(Character::isISOControl);
    }

    static void passwordPolicy(String password) {
        if (!validPassword(password)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Password must contain 15 to 128 Unicode characters and no control characters");
    }

    @Transactional
    public void register(String email, String password) {
        passwordPolicy(password);
        String encoded = encoder.encode(password);
        int inserted = jdbc.update("INSERT INTO users(username, password, enabled) VALUES (?, ?, false) ON CONFLICT DO NOTHING", email, encoded);
        if (inserted == 0) return;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, 'ROLE_USER')", email);
        jdbc.update("INSERT INTO accounts(id, username) VALUES (?, ?)", id, email);
        issue(id, email, "VERIFY");
    }

    @Transactional
    public void requestToken(String email, String purpose) {
        var accounts = jdbc.query("SELECT id, verified FROM accounts WHERE username = ? FOR UPDATE",
            (row, n) -> new Account(row.getObject("id", UUID.class), email, row.getBoolean("verified")), email);
        if (accounts.isEmpty()) return;
        var account = accounts.getFirst();
        if (purpose.equals("VERIFY") && account.verified()) return;
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE account_id = ? AND created_at > now() - interval '60 seconds'", Integer.class, account.id());
        if (recent != null && recent > 0) return;
        issue(account.id(), email, purpose);
    }

    private void issue(UUID id, String email, String purpose) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM account_tokens WHERE account_id = ? AND purpose = ?", id, purpose);
        jdbc.update("INSERT INTO account_tokens(token_hash, account_id, purpose, expires_at) VALUES (?, ?, ?, now() + interval '30 minutes')", hash(token), id, purpose);
        String subject = purpose.equals("VERIFY") ? "Verify your Plot Twist account" : "Reset your Plot Twist password";
        String body = subject + "\n\nOne-time code (valid for 30 minutes):\n" + token
            + "\n\nUse this code in the Plot Twist CLI or API. If you did not request this message, ignore it. Never share this code.";
        jdbc.update("INSERT INTO email_outbox(id, recipient, subject, body) VALUES (?, ?, ?, ?)", UUID.randomUUID(), email, subject, body);
    }

    @Transactional
    public void verify(String token) {
        var account = consume(token, "VERIFY");
        jdbc.update("UPDATE accounts SET verified = true WHERE id = ?", account.id());
        if (!account.verified()) jdbc.update("UPDATE users SET enabled = true WHERE username = ?", account.username());
    }

    @Transactional
    public void reset(String token, String password) {
        passwordPolicy(password);
        var account = consume(token, "RESET");
        jdbc.update("UPDATE users SET password = ?, enabled = CASE WHEN ? THEN enabled ELSE true END WHERE username = ?",
            encoder.encode(password), account.verified(), account.username());
        jdbc.update("UPDATE accounts SET verified = true WHERE id = ?", account.id());
        jdbc.update("DELETE FROM account_tokens WHERE account_id = ?", account.id());
        jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", account.username());
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", account.username());
        jdbc.update("DELETE FROM login_attempts WHERE username = ?", account.username());
    }

    private Account consume(String token, String purpose) {
        var accounts = jdbc.query("""
            SELECT a.id, a.username, a.verified FROM account_tokens t JOIN accounts a ON a.id = t.account_id
            WHERE t.token_hash = ? AND t.purpose = ? AND t.expires_at > now() FOR UPDATE OF t, a
            """, (row, n) -> new Account(row.getObject("id", UUID.class), row.getString("username"), row.getBoolean("verified")), hash(token), purpose);
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired one-time code");
        jdbc.update("DELETE FROM account_tokens WHERE token_hash = ?", hash(token));
        return accounts.getFirst();
    }

    private static byte[] hash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Account(UUID id, String username, boolean verified) {}
}
