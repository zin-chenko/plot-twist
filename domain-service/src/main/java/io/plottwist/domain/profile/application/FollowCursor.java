package io.plottwist.domain.profile.application;

import io.plottwist.domain.profile.model.FollowPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class FollowCursor {
  private FollowCursor() {}

  static FollowPosition decode(String cursor, UUID account, boolean followers) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 240) throw new IllegalArgumentException();
      var parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 5
          || !parts[0].equals("1")
          || !parts[1].equals(account.toString())
          || !parts[2].equals(followers ? "followers" : "following"))
        throw new IllegalArgumentException();
      Instant created = Instant.parse(parts[3]);
      if (created.isBefore(Instant.EPOCH) || created.isAfter(Instant.parse("9999-12-31T23:59:59Z")))
        throw new IllegalArgumentException();
      return new FollowPosition(created, UUID.fromString(parts[4]));
    } catch (RuntimeException invalid) {
      throw new ProfileException(
          ProfileException.Reason.INVALID_INPUT, "Invalid connection page cursor");
    }
  }

  static String encode(UUID account, boolean followers, FollowPosition position) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            ("1|"
                    + account
                    + "|"
                    + (followers ? "followers" : "following")
                    + "|"
                    + position.createdAt()
                    + "|"
                    + position.accountId())
                .getBytes(StandardCharsets.UTF_8));
  }
}
