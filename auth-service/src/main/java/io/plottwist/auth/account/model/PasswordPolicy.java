package io.plottwist.auth.account.model;

public final class PasswordPolicy {
  private PasswordPolicy() {}

  public static boolean isValid(String password) {
    if (password == null || password.isBlank()) return false;
    int length = password.codePointCount(0, password.length());
    return length >= 15
        && length <= 128
        && password.codePoints().noneMatch(Character::isISOControl);
  }

  public static void requireValid(String password) {
    if (!isValid(password))
      throw new AccountValidationException(
          "Password must contain 15 to 128 Unicode characters and no control characters");
  }
}
