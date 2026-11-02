package io.plottwist.domain.profile.application;

import io.plottwist.domain.profile.model.FollowPosition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

final class FollowCursor {
  private FollowCursor() {}

  static FollowPosition decode(String cursor, UUID account, boolean followers, String query) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 240) throw new IllegalArgumentException();
      var parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      boolean legacy = parts.length == 5 && parts[0].equals("1") && query.isEmpty();
      if ((!legacy
              && (parts.length != 6
                  || !parts[0].equals("2")
                  || !parts[3].equals(fingerprint(query))))
          || !parts[1].equals(account.toString())
          || !parts[2].equals(followers ? "followers" : "following"))
        throw new IllegalArgumentException();
      int offset = legacy ? 3 : 4;
      Instant created = Instant.parse(parts[offset]);
      if (created.isBefore(Instant.EPOCH) || created.isAfter(Instant.parse("9999-12-31T23:59:59Z")))
        throw new IllegalArgumentException();
      return new FollowPosition(created, UUID.fromString(parts[offset + 1]));
    } catch (RuntimeException invalid) {
      throw new ProfileException(
          ProfileException.Reason.INVALID_INPUT, "Invalid connection page cursor");
    }
  }

  static String encode(UUID account, boolean followers, FollowPosition position, String query) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            ("2|"
                    + account
                    + "|"
                    + (followers ? "followers" : "following")
                    + "|"
                    + fingerprint(query)
                    + "|"
                    + position.createdAt()
                    + "|"
                    + position.accountId())
                .getBytes(StandardCharsets.UTF_8));
  }

  private static String fingerprint(String query) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
