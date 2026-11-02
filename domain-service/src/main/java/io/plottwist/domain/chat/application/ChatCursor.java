package io.plottwist.domain.chat.application;

import io.plottwist.domain.chat.model.ChatPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class ChatCursor {
  private ChatCursor() {}

  static ChatPosition decode(String cursor, UUID viewer) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 240) throw new IllegalArgumentException();
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 4 || !parts[0].equals("1") || !parts[1].equals(viewer.toString()))
        throw new IllegalArgumentException();
      var time = Instant.parse(parts[2]);
      if (time.isBefore(Instant.EPOCH) || time.isAfter(Instant.parse("9999-12-31T23:59:59Z")))
        throw new IllegalArgumentException();
      return new ChatPosition(time, UUID.fromString(parts[3]));
    } catch (RuntimeException invalid) {
      throw new ChatException(ChatException.Reason.INVALID, "Invalid chat page cursor");
    }
  }

  static String encode(UUID viewer, ChatSummaryKey key) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            ("1|" + viewer + "|" + key.time() + "|" + key.id()).getBytes(StandardCharsets.UTF_8));
  }

  record ChatSummaryKey(Instant time, UUID id) {}
}
