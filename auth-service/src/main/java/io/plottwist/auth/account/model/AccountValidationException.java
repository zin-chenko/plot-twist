package io.plottwist.auth.account.model;

public final class AccountValidationException extends RuntimeException {
  public AccountValidationException(String message) {
    super(message);
  }
}
