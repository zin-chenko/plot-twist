package io.plottwist.domain.story.application;

import static io.plottwist.domain.story.application.StoryException.Reason.*;

import io.plottwist.domain.story.application.port.*;
import io.plottwist.domain.story.model.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {
  private final CommentRepository comments;
  private final StoryRepository stories;

  public CommentService(CommentRepository comments, StoryRepository stories) {
    this.comments = comments;
    this.stories = stories;
  }

  public FeedPage<StoryComment> page(
      UUID story, UUID viewer, UUID root, String order, String cursor, int limit) {
    if (limit < 1 || limit > 20) throw invalid("Page size must be between 1 and 20");
    available(story, viewer);
    CommentSort sort;
    try {
      sort = CommentSort.valueOf(order.toUpperCase(Locale.ROOT));
    } catch (RuntimeException invalid) {
      throw invalid("Use newest, oldest or popular sorting");
    }
    if (root != null) {
      if (get(story, root, viewer).rootId() != null)
        throw invalid("Load replies using the root comment ID");
      sort = CommentSort.OLDEST;
    }
    var position = CommentCursor.decode(cursor, viewer, story, root, sort);
    if (sort == CommentSort.POPULAR) {
      UUID batch =
          position.batch() == null ? comments.createRanking(story, viewer) : position.batch();
      if (!comments.rankingExists(batch, story, viewer))
        throw invalid("Comment ranking expired; refresh the discussion");
      var rows = comments.rankedPage(batch, viewer, position.rank(), limit + 1);
      var selected = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
      return new FeedPage<>(
          selected.stream().map(RankedComment::comment).toList(),
          rows.size() > limit
              ? CommentCursor.popular(viewer, story, batch, selected.getLast().position())
              : null);
    }
    var rows = comments.page(story, viewer, root, sort, position.date(), limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    return new FeedPage<>(
        items,
        rows.size() > limit
            ? CommentCursor.date(viewer, story, root, sort, items.getLast())
            : null);
  }

  public StoryComment get(UUID story, UUID comment, UUID viewer) {
    available(story, viewer);
    return find(story, comment, viewer);
  }

  @Transactional
  public StoryComment create(UUID story, UUID author, String body, UUID parent) {
    body = text(body);
    lockStory(story);
    UUID root = null;
    if (parent != null) {
      var target = locked(story, parent, author);
      root = target.rootId() == null ? target.id() : target.rootId();
    }
    UUID id = UUID.randomUUID();
    comments.create(id, story, author, body, parent, root);
    return find(story, id, author);
  }

  @Transactional
  public StoryComment update(UUID story, UUID comment, UUID author, long version, String body) {
    body = text(body);
    lockStory(story);
    var current = locked(story, comment, author);
    own(current, author);
    if (!comments.update(story, comment, author, version, body))
      throw new StoryException(VERSION_CHANGED, "Comment changed; reload before editing");
    return find(story, comment, author);
  }

  @Transactional
  public StoryComment like(UUID story, UUID comment, UUID actor, boolean liked) {
    lockStory(story);
    locked(story, comment, actor);
    comments.like(comment, actor, liked);
    return find(story, comment, actor);
  }

  @Transactional
  public void delete(UUID story, UUID comment, UUID author) {
    lockStory(story);
    var current = locked(story, comment, author);
    own(current, author);
    comments.delete(story, comment);
  }

  private StoryComment locked(UUID story, UUID comment, UUID viewer) {
    var candidate = find(story, comment, viewer);
    UUID root = candidate.rootId() == null ? candidate.id() : candidate.rootId();
    if (!comments.lock(story, root) || !root.equals(comment) && !comments.lock(story, comment))
      throw missing();
    var current = find(story, comment, viewer);
    if (current.deleted()) throw missing();
    return current;
  }

  private StoryComment find(UUID story, UUID comment, UUID viewer) {
    return comments.find(story, comment, viewer).orElseThrow(CommentService::missing);
  }

  private void available(UUID story, UUID viewer) {
    if (stories.find(story, viewer).filter(s -> s.publishedVersion() != null).isEmpty())
      throw new StoryException(NOT_FOUND, "Story not found");
  }

  private void lockStory(UUID story) {
    if (!stories.lockForInteraction(story)) throw new StoryException(NOT_FOUND, "Story not found");
  }

  private static void own(StoryComment comment, UUID account) {
    if (!comment.authorId().equals(account))
      throw new StoryException(FORBIDDEN, "Only the author can change this comment");
  }

  private static String text(String body) {
    if (body == null
        || body.isBlank()
        || body.length() > 2000
        || body.codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
      throw invalid("Comment must contain 1–2000 text characters");
    return body.strip();
  }

  private static StoryException invalid(String detail) {
    return new StoryException(INVALID_INPUT, detail);
  }

  private static StoryException missing() {
    return new StoryException(NOT_FOUND, "Comment not found");
  }
}
