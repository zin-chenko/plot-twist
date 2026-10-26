package io.plottwist.auth.account.api.request;

import jakarta.validation.constraints.*;

public record TokenRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
  @Override
  public String toString() {
    return "TokenRequest[redacted]";
  }
}
