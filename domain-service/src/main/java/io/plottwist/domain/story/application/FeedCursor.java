package io.plottwist.domain.story.application;

import io.plottwist.domain.story.model.PagePosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class FeedCursor {
  private FeedCursor() {}

  static PagePosition decode(String cursor) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 180) throw new IllegalArgumentException();
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      Instant created = Instant.parse(parts[0]);
      if (created.isBefore(Instant.EPOCH) || created.isAfter(Instant.parse("9999-12-31T23:59:59Z")))
        throw new IllegalArgumentException();
      return new PagePosition(created, UUID.fromString(parts[1]));
    } catch (RuntimeException invalid) {
      throw new StoryException(StoryException.Reason.INVALID_INPUT, "Invalid page cursor");
    }
  }

  static String encode(Instant created, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((created + "|" + id).getBytes(StandardCharsets.UTF_8));
  }
}
