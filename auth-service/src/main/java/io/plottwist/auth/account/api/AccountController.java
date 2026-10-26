package io.plottwist.auth.account.api;

import io.plottwist.auth.account.api.request.*;
import io.plottwist.auth.account.application.AccountService;
import io.plottwist.auth.account.model.TokenPurpose;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/v1")
public class AccountController {
  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping("/registrations")
  ResponseEntity<Void> register(@Valid @RequestBody RegistrationRequest body) {
    accounts.register(body.email(), body.password());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/email-verifications/requests")
  ResponseEntity<Void> resend(@Valid @RequestBody EmailRequest body) {
    accounts.requestToken(body.email(), TokenPurpose.VERIFY);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/email-verifications")
  ResponseEntity<Void> verify(@Valid @RequestBody TokenRequest body) {
    accounts.verify(body.token());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password-resets/requests")
  ResponseEntity<Void> forgot(@Valid @RequestBody EmailRequest body) {
    accounts.requestToken(body.email(), TokenPurpose.RESET);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/password-resets")
  ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest body) {
    accounts.reset(body.token(), body.password());
    return ResponseEntity.noContent().build();
  }
}
