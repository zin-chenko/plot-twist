package io.plottwist.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/v1")
public class AccountController {
    private final AccountService accounts;

    AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping("/registrations")
    ResponseEntity<Void> register(@Valid @RequestBody Registration body) {
        accounts.register(body.email(), body.password());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email-verifications/requests")
    ResponseEntity<Void> resend(@Valid @RequestBody EmailRequest body) {
        accounts.requestToken(body.email(), "VERIFY");
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email-verifications")
    ResponseEntity<Void> verify(@Valid @RequestBody Code body) {
        accounts.verify(body.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-resets/requests")
    ResponseEntity<Void> forgot(@Valid @RequestBody EmailRequest body) {
        accounts.requestToken(body.email(), "RESET");
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-resets")
    ResponseEntity<Void> reset(@Valid @RequestBody Reset body) {
        accounts.reset(body.token(), body.password());
        return ResponseEntity.noContent().build();
    }

    public record Registration(@NotBlank @Email @Size(max=254) String email,
                               @StrongPassword String password) {
        public Registration { if (email != null) email = AccountService.email(email); }
        @Override public String toString() { return "Registration[redacted]"; }
    }
    public record EmailRequest(@NotBlank @Email @Size(max=254) String email) {
        public EmailRequest { if (email != null) email = AccountService.email(email); }
        @Override public String toString() { return "EmailRequest[redacted]"; }
    }
    public record Code(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "Code[redacted]"; }
    }
    public record Reset(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
                        @StrongPassword String password) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
}
