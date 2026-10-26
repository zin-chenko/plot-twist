package io.plottwist.auth.account.infrastructure.persistence;

import io.plottwist.auth.account.application.port.AccountRepository;
import io.plottwist.auth.account.model.Account;
import io.plottwist.auth.account.model.TokenPurpose;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {
  private final JdbcTemplate jdbc;

  public JdbcAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean createUser(String email, String encodedPassword) {
    return jdbc.update(
            "INSERT INTO users(username, password, enabled) VALUES (?, ?, false) ON CONFLICT DO"
                + " NOTHING",
            email,
            encodedPassword)
        == 1;
  }

  @Override
  public void createAccount(UUID id, String email) {
    jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, 'ROLE_USER')", email);
    jdbc.update("INSERT INTO accounts(id, username) VALUES (?, ?)", id, email);
  }

  @Override
  public Optional<Account> lockAccount(String email) {
    return jdbc
        .query(
            "SELECT id, verified FROM accounts WHERE username = ? FOR UPDATE",
            (row, n) ->
                new Account(row.getObject("id", UUID.class), email, row.getBoolean("verified")),
            email)
        .stream()
        .findFirst();
  }

  @Override
  public boolean hasRecentToken(UUID accountId) {
    Integer recent =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_tokens WHERE account_id = ? AND created_at > now() -"
                + " interval '60 seconds'",
            Integer.class,
            accountId);
    return recent != null && recent > 0;
  }

  @Override
  public void replaceTokenHash(UUID accountId, TokenPurpose purpose, byte[] hash) {
    jdbc.update(
        "DELETE FROM account_tokens WHERE account_id = ? AND purpose = ?",
        accountId,
        purpose.name());
    jdbc.update(
        "INSERT INTO account_tokens(token_hash, account_id, purpose, expires_at) VALUES (?, ?, ?,"
            + " now() + interval '30 minutes')",
        hash,
        accountId,
        purpose.name());
  }

  @Override
  public Optional<Account> consumeToken(byte[] hash, TokenPurpose purpose) {
    var accounts =
        jdbc.query(
            """
            SELECT a.id, a.username, a.verified FROM account_tokens t JOIN accounts a ON a.id = t.account_id
            WHERE t.token_hash = ? AND t.purpose = ? AND t.expires_at > now() FOR UPDATE OF t, a
            """,
            (row, n) ->
                new Account(
                    row.getObject("id", UUID.class),
                    row.getString("username"),
                    row.getBoolean("verified")),
            hash,
            purpose.name());
    if (accounts.isEmpty()) return Optional.empty();
    jdbc.update("DELETE FROM account_tokens WHERE token_hash = ?", hash);
    return Optional.of(accounts.getFirst());
  }

  @Override
  public void verify(Account account) {
    jdbc.update("UPDATE accounts SET verified = true WHERE id = ?", account.id());
    if (!account.verified())
      jdbc.update("UPDATE users SET enabled = true WHERE username = ?", account.username());
  }

  @Override
  public void resetPasswordAndSessions(Account account, String encodedPassword) {
    jdbc.update(
        "UPDATE users SET password = ?, enabled = CASE WHEN ? THEN enabled ELSE true END WHERE"
            + " username = ?",
        encodedPassword,
        account.verified(),
        account.username());
    jdbc.update("UPDATE accounts SET verified = true WHERE id = ?", account.id());
    jdbc.update("DELETE FROM account_tokens WHERE account_id = ?", account.id());
    jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", account.username());
    jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", account.username());
    jdbc.update("DELETE FROM login_attempts WHERE username = ?", account.username());
  }
}
