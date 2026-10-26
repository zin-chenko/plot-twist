package io.plottwist.auth.account.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
  @Test
  void handlesUnicodeWithoutTruncation() {
    assertThrows(AccountValidationException.class, () -> PasswordPolicy.requireValid("short"));
    assertThrows(
        AccountValidationException.class, () -> PasswordPolicy.requireValid("x".repeat(129)));
    assertDoesNotThrow(() -> PasswordPolicy.requireValid("界".repeat(128)));
    assertFalse(PasswordPolicy.isValid(" ".repeat(20)));
  }
}
