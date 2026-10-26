package io.plottwist.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ProductionSafetyTest {
    @TempDir Path directory;

    private MockEnvironment config() throws Exception {
        Path key = directory.resolve("signing.pk8");
        Files.write(key, new byte[]{1}); // Cryptographic validity is separately tested by SigningKeysTest.
        return new MockEnvironment().withProperty("auth.issuer", "https://example.test")
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("spring.mail.host", "smtp.example.test")
            .withProperty("spring.mail.properties.mail.smtp.auth", "false")
            .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
            .withProperty("auth.signing-key", key.toString());
    }

    @Test
    void acceptsExplicitProductionConfiguration() throws Exception {
        assertDoesNotThrow(() -> ProductionSafety.validate(config()));
    }

    @Test
    void refusesDevelopmentIssuerAndBootstrap() throws Exception {
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(config().withProperty("auth.issuer", "http://example.test")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(config().withProperty("auth.bootstrap.enabled", "true")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(config().withProperty("auth.auto-create-key", "true")));
        assertThrows(IllegalStateException.class, () -> ProductionSafety.validate(config().withProperty("spring.mail.host", "mailpit")));
    }
}
