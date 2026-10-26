package io.plottwist.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordEncodingTest {
  @Test
  void encodesArgon2idAndStillVerifiesLegacyBcrypt() {
    var encoder = new CredentialConfig().passwordEncoder();
    String password = "界".repeat(100);
    String hash = encoder.encode(password);
    assertTrue(hash.startsWith("{argon2}$argon2id$"));
    assertTrue(encoder.matches(password, hash));
    assertFalse(encoder.matches(password + "other", hash));
    String old =
        "{bcrypt}"
            + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("Legacy-long-password");
    assertTrue(encoder.matches("Legacy-long-password", old));
  }
}
