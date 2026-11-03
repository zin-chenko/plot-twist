package io.plottwist.domain.story.application;

import io.plottwist.domain.story.model.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class CommentCursor {
  record Position(PagePosition date, UUID batch, int rank) {}

  static Position decode(String cursor, UUID viewer, UUID story, UUID root, CommentSort sort) {
    if (cursor == null) return new Position(null, null, 0);
    try {
      if (cursor.length() > 400) throw new IllegalArgumentException();
      var p =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (p.length != 7
          || !p[1].equals(viewer.toString())
          || !p[2].equals(story.toString())
          || !p[3].equals(root == null ? "roots" : root.toString())
          || !p[4].equals(sort.name())) throw new IllegalArgumentException();
      if (sort == CommentSort.POPULAR) {
        if (!p[0].equals("p")) throw new IllegalArgumentException();
        int rank = Integer.parseInt(p[6]);
        if (rank < 1 || rank > 500) throw new IllegalArgumentException();
        return new Position(null, UUID.fromString(p[5]), rank);
      }
      if (!p[0].equals("d")) throw new IllegalArgumentException();
      Instant date = Instant.parse(p[5]);
      if (date.isBefore(Instant.EPOCH) || date.isAfter(Instant.parse("9999-12-31T23:59:59Z")))
        throw new IllegalArgumentException();
      return new Position(new PagePosition(date, UUID.fromString(p[6])), null, 0);
    } catch (RuntimeException invalid) {
      throw new StoryException(
          StoryException.Reason.INVALID_INPUT, "Invalid comment cursor; refresh the discussion");
    }
  }

  static String date(UUID viewer, UUID story, UUID root, CommentSort sort, StoryComment last) {
    return encode(
        "d", viewer, story, root, sort, last.createdAt().toString(), last.id().toString());
  }

  static String popular(UUID viewer, UUID story, UUID batch, int rank) {
    return encode(
        "p", viewer, story, null, CommentSort.POPULAR, batch.toString(), Integer.toString(rank));
  }

  private static String encode(
      String type,
      UUID viewer,
      UUID story,
      UUID root,
      CommentSort sort,
      String first,
      String last) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (type
                    + "|"
                    + viewer
                    + "|"
                    + story
                    + "|"
                    + (root == null ? "roots" : root)
                    + "|"
                    + sort.name()
                    + "|"
                    + first
                    + "|"
                    + last)
                .getBytes(StandardCharsets.UTF_8));
  }
}
