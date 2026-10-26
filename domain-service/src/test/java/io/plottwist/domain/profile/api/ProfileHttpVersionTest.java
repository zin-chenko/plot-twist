package io.plottwist.domain.profile.api;

import static org.junit.jupiter.api.Assertions.*;

import io.plottwist.domain.profile.model.Profile;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProfileHttpVersionTest {
  @Test
  void etagsUseActualQuotes() {
    String etag = Character.toString('"') + "7" + '"';
    assertEquals(7L, ProfileController.version(etag));
    var profile =
        new Profile(
            java.util.UUID.randomUUID(),
            "User",
            null,
            null,
            null,
            null,
            7L,
            java.time.Instant.now(),
            java.time.Instant.now(),
            null);
    assertEquals(etag, ProfileController.response(profile).getHeaders().getETag());
    assertEquals(
        428,
        assertThrows(ResponseStatusException.class, () -> ProfileController.version(null))
            .getStatusCode()
            .value());
  }
}
