package io.plottwist.auth.account.api.request;

import io.plottwist.auth.account.api.validation.StrongPassword;
import io.plottwist.auth.account.model.EmailAddress;
import jakarta.validation.constraints.*;

public record RegistrationRequest(
    @NotBlank @Email @Size(max = 254) String email, @StrongPassword String password) {
  public RegistrationRequest {
    if (email != null) email = EmailAddress.normalize(email);
  }

  @Override
  public String toString() {
    return "RegistrationRequest[redacted]";
  }
}
