package io.plottwist.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test
    void handlesUnicodeWithoutTruncation() {
        assertThrows(ResponseStatusException.class, () -> AccountService.passwordPolicy("short"));
        assertThrows(ResponseStatusException.class, () -> AccountService.passwordPolicy("x".repeat(129)));
        assertDoesNotThrow(() -> AccountService.passwordPolicy("界".repeat(128)));
        assertFalse(AccountService.validPassword(" ".repeat(20)));
    }

    @Test
    void encodesArgon2idAndStillVerifiesLegacyBcrypt() {
        var encoder = new AuthConfig().passwordEncoder();
        String password = "界".repeat(100);
        String hash = encoder.encode(password);
        assertTrue(hash.startsWith("{argon2}$argon2id$"));
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches(password + "other", hash));
        String old = "{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Legacy-long-password");
        assertTrue(encoder.matches("Legacy-long-password", old));
    }
}
