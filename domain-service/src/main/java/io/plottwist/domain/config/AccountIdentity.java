package io.plottwist.domain.config;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public final class AccountIdentity {
  private AccountIdentity() {}

  public static UUID require(Jwt jwt) {
    String account = jwt.getClaimAsString("account_id");
    if (account == null || !account.equals(jwt.getSubject()))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "A verified user account is required");
    try {
      return UUID.fromString(account);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid account identity");
    }
  }
}
