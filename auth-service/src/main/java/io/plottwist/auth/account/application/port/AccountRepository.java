package io.plottwist.auth.account.application.port;

import io.plottwist.auth.account.model.Account;
import io.plottwist.auth.account.model.TokenPurpose;
import java.util.Optional;
import java.util.UUID;

/** Mutations and row locks participate in the account use case's transaction. */
public interface AccountRepository {
  boolean createUser(String email, String encodedPassword);

  void createAccount(UUID id, String email);

  Optional<Account> lockAccount(String email);

  boolean hasRecentToken(UUID accountId);

  void replaceTokenHash(UUID accountId, TokenPurpose purpose, byte[] hash);

  Optional<Account> consumeToken(byte[] hash, TokenPurpose purpose);

  void verify(Account account);

  void resetPasswordAndSessions(Account account, String encodedPassword);
}
