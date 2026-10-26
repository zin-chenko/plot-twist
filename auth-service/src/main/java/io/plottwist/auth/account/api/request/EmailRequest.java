package io.plottwist.auth.account.api.request;

import io.plottwist.auth.account.model.EmailAddress;
import jakarta.validation.constraints.*;

public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
  public EmailRequest {
    if (email != null) email = EmailAddress.normalize(email);
  }

  @Override
  public String toString() {
    return "EmailRequest[redacted]";
  }
}
