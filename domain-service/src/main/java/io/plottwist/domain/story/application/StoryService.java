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

  public FeedPage<Story> feed(UUID viewer, boolean mine, boolean liked, String cursor, int limit) {
    checkLimit(limit);
    var rows =
        stories.feed(viewer, mine ? viewer : null, liked, FeedCursor.decode(cursor), limit + 1);
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

  public FeedPage<Story> following(UUID viewer, String cursor, int limit) {
    checkLimit(limit);
    var rows = stories.following(viewer, FeedCursor.decode(cursor), limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    var last = items.isEmpty() ? null : items.getLast();
    return new FeedPage<>(
        items, rows.size() > limit ? FeedCursor.encode(last.createdAt(), last.id()) : null);
  }

  public FeedPage<Story> publishedByAuthor(UUID viewer, UUID author, String cursor, int limit) {
    checkLimit(limit);
    var rows = stories.publishedByAuthor(viewer, author, FeedCursor.decode(cursor), limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    var last = items.isEmpty() ? null : items.getLast();
    return new FeedPage<>(
        items, rows.size() > limit ? FeedCursor.encode(last.createdAt(), last.id()) : null);
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
    var validated = new StoryContent(text(content.title(), 160), text(content.body(), 20000));
    try {
      io.plottwist.moderation.contract.MediaMarkup.ids(validated.body());
    } catch (IllegalArgumentException invalid) {
      throw new StoryException(INVALID_INPUT, invalid.getMessage());
    }
    return validated;
  }

  private static String text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value
            .codePoints()
            .anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t'))
      throw new StoryException(
          INVALID_INPUT, "Text is empty, too long or contains unsupported characters");
    return value.strip();
  }

  private static void checkLimit(int limit) {
    if (limit < 1 || limit > 20)
      throw new StoryException(INVALID_INPUT, "Page size must be between 1 and 20");
  }
}
