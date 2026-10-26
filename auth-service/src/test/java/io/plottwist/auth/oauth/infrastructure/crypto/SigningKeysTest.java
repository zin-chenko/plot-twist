package io.plottwist.auth.oauth.infrastructure.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeysTest {
  @TempDir Path directory;

  @Test
  void restartKeepsTheSameSigningIdentity() throws Exception {
    var path = directory.resolve("keys/signing.pk8");
    var first = SigningKeys.loadOrCreate(path);
    var second = SigningKeys.loadOrCreate(path);
    assertEquals(first.getKeyID(), second.getKeyID());
    assertEquals(first.toPublicJWK(), second.toPublicJWK());
    assertTrue(first.isPrivate());
    assertFalse(first.toPublicJWK().isPrivate());
  }

  @Test
  void corruptedKeyFailsRatherThanSilentlyRotating() throws Exception {
    var path = directory.resolve("broken.pk8");
    Files.writeString(path, "invalid key");
    assertThrows(Exception.class, () -> SigningKeys.loadOrCreate(path));
    assertEquals("invalid key", Files.readString(path));
  }
}
