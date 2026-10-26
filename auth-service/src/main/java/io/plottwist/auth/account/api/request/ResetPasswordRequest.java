package io.plottwist.auth.account.api.request;

import io.plottwist.auth.account.api.validation.StrongPassword;
import jakarta.validation.constraints.*;

public record ResetPasswordRequest(
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token, @StrongPassword String password) {
  @Override
  public String toString() {
    return "ResetPasswordRequest[redacted]";
  }
}
