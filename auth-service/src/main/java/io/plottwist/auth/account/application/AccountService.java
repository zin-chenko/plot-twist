package io.plottwist.auth.account.application;

import io.plottwist.auth.account.application.port.AccountRepository;
import io.plottwist.auth.account.model.*;
import io.plottwist.auth.notification.application.port.EmailOutbox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final AccountRepository accounts;
  private final EmailOutbox emails;
  private final PasswordEncoder encoder;
  private final SecureRandom random = new SecureRandom();

  public AccountService(AccountRepository accounts, EmailOutbox emails, PasswordEncoder encoder) {
    this.accounts = accounts;
    this.emails = emails;
    this.encoder = encoder;
  }

  @Transactional
  public void register(String email, String password) {
    PasswordPolicy.requireValid(password);
    email = EmailAddress.normalize(email);
    if (!accounts.createUser(email, encoder.encode(password))) return;
    UUID id = UUID.randomUUID();
    accounts.createAccount(id, email);
    issue(id, email, TokenPurpose.VERIFY);
  }

  @Transactional
  public void requestToken(String email, TokenPurpose purpose) {
    email = EmailAddress.normalize(email);
    var found = accounts.lockAccount(email);
    if (found.isEmpty()) return;
    var account = found.get();
    if (purpose == TokenPurpose.VERIFY && account.verified()) return;
    if (accounts.hasRecentToken(account.id())) return;
    issue(account.id(), email, purpose);
  }

  private void issue(UUID id, String email, TokenPurpose purpose) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    accounts.replaceTokenHash(id, purpose, hash(token));
    String subject =
        purpose == TokenPurpose.VERIFY
            ? "Verify your Plot Twist account"
            : "Reset your Plot Twist password";
    String body =
        subject
            + "\n\nOne-time code (valid for 30 minutes):\n"
            + token
            + "\n\n"
            + "Use this code in the Plot Twist CLI or API. If you did not request this message,"
            + " ignore it. Never share this code.";
    emails.enqueue(email, subject, body);
  }

  @Transactional
  public void verify(String token) {
    accounts.verify(consume(token, TokenPurpose.VERIFY));
  }

  @Transactional
  public void reset(String token, String password) {
    PasswordPolicy.requireValid(password);
    var account = consume(token, TokenPurpose.RESET);
    accounts.resetPasswordAndSessions(account, encoder.encode(password));
  }

  private Account consume(String token, TokenPurpose purpose) {
    return accounts
        .consumeToken(hash(token), purpose)
        .orElseThrow(() -> new AccountValidationException("Invalid or expired one-time code"));
  }

  private static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
