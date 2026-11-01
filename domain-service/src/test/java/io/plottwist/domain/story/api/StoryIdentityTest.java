package io.plottwist.domain.story.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class StoryIdentityTest {
  @Test
  void requiresVerifiedUserBoundToSubject() {
    var id = UUID.randomUUID().toString();
    var valid =
        Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject(id)
            .claim("account_id", id)
            .build();
    assertEquals(UUID.fromString(id), AccountIdentity.require(valid));
    var machine = Jwt.withTokenValue("test").header("alg", "RS256").subject("machine").build();
    assertEquals(
        403,
        assertThrows(ResponseStatusException.class, () -> AccountIdentity.require(machine))
            .getStatusCode()
            .value());
    var forged =
        Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject(UUID.randomUUID().toString())
            .claim("account_id", id)
            .build();
    assertEquals(
        403,
        assertThrows(ResponseStatusException.class, () -> AccountIdentity.require(forged))
            .getStatusCode()
            .value());
  }

  @Test
  void validatesConditionalWrites() {
    assertEquals(4, StoryController.version("\"4\""));
    assertEquals(
        428,
        assertThrows(ResponseStatusException.class, () -> StoryController.version(null))
            .getStatusCode()
            .value());
    for (String bad : java.util.List.of("*", "4", "W/\"4\"", "\"-1\""))
      assertEquals(
          400,
          assertThrows(ResponseStatusException.class, () -> StoryController.version(bad))
              .getStatusCode()
              .value());
  }
}
