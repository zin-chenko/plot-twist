package io.plottwist.domain.story.application;

import static io.plottwist.domain.story.application.StoryException.Reason.*;

import io.plottwist.domain.story.application.port.StoryRepository;
import io.plottwist.domain.story.model.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryService {
  private final StoryRepository stories;

  public StoryService(StoryRepository stories) {
    this.stories = stories;
  }

  public FeedPage<Story> feed(UUID viewer, boolean mine, String cursor, int limit) {
    checkLimit(limit);
    var rows = stories.feed(viewer, mine ? viewer : null, FeedCursor.decode(cursor), limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    var last = items.isEmpty() ? null : items.getLast();
    return new FeedPage<>(
        items, rows.size() > limit ? FeedCursor.encode(last.createdAt(), last.id()) : null);
  }

  public Story get(UUID id, UUID viewer) {
    return stories
        .find(id, viewer)
        .orElseThrow(() -> new StoryException(NOT_FOUND, "Story not found"));
  }

  @Transactional
  public Story create(UUID author, StoryContent content) {
    content = validate(content);
    UUID id = UUID.randomUUID();
    stories.create(id, author, content);
    return get(id, author);
  }

  @Transactional
  public Story update(UUID id, UUID author, long version, StoryContent content) {
    own(get(id, author).authorId(), author);
    if (!stories.update(id, author, version, validate(content))) throw changed();
    return get(id, author);
  }

  @Transactional
  public void delete(UUID id, UUID author, long version) {
    own(get(id, author).authorId(), author);
    if (!stories.delete(id, author, version)) throw changed();
  }

  @Transactional
  public Story react(UUID id, UUID account, int value) {
    if (value < -1 || value > 1)
      throw new StoryException(INVALID_INPUT, "Reaction must be -1, 0 or 1");
    lock(id);
    stories.react(id, account, value);
    return get(id, account);
  }

  public FeedPage<StoryComment> comments(UUID id, UUID viewer, String cursor, int limit) {
    checkLimit(limit);
    get(id, viewer);
    var rows = stories.comments(id, FeedCursor.decode(cursor), limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    var last = items.isEmpty() ? null : items.getLast();
    return new FeedPage<>(
        items, rows.size() > limit ? FeedCursor.encode(last.createdAt(), last.id()) : null);
  }

  @Transactional
  public StoryComment comment(UUID id, UUID author, String body) {
    body = text(body, 2000);
    lock(id);
    return stories.comment(UUID.randomUUID(), id, author, body);
  }

  @Transactional
  public void deleteComment(UUID story, UUID comment, UUID author) {
    var owner =
        stories
            .commentAuthor(story, comment)
            .orElseThrow(() -> new StoryException(NOT_FOUND, "Comment not found"));
    own(owner, author);
    if (!stories.deleteComment(story, comment, author))
      throw new StoryException(NOT_FOUND, "Comment not found");
  }

  private void lock(UUID id) {
    if (!stories.lockForInteraction(id)) throw new StoryException(NOT_FOUND, "Story not found");
  }

  private static void own(UUID owner, UUID account) {
    if (!owner.equals(account))
      throw new StoryException(FORBIDDEN, "Only the author can change this content");
  }

  private static StoryException changed() {
    return new StoryException(VERSION_CHANGED, "Story changed; reload before editing");
  }

  private static StoryContent validate(StoryContent content) {
    return new StoryContent(text(content.title(), 160), text(content.body(), 20000));
  }

  private static String text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max || value.indexOf(0) >= 0)
      throw new StoryException(
          INVALID_INPUT, "Text is empty, too long or contains unsupported characters");
    return value.strip();
  }

  private static void checkLimit(int limit) {
    if (limit < 1 || limit > 20)
      throw new StoryException(INVALID_INPUT, "Page size must be between 1 and 20");
  }
}
