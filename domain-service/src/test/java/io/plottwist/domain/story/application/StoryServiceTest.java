package io.plottwist.domain.story.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.plottwist.domain.story.application.port.StoryRepository;
import io.plottwist.domain.story.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class StoryServiceTest {
  private final StoryRepository repository = mock(StoryRepository.class);
  private final StoryService service = new StoryService(repository);

  @Test
  void refusesForeignEditsAndDeletesBeforeWriting() {
    UUID id = UUID.randomUUID(), author = UUID.randomUUID(), other = UUID.randomUUID();
    when(repository.find(id, other)).thenReturn(Optional.of(story(id, author)));
    assertEquals(
        StoryException.Reason.FORBIDDEN,
        assertThrows(
                StoryException.class,
                () -> service.update(id, other, 0, new StoryContent("Stolen", "Text")))
            .reason());
    assertEquals(
        StoryException.Reason.FORBIDDEN,
        assertThrows(StoryException.class, () -> service.delete(id, other, 0)).reason());
    verify(repository, never()).update(any(), any(), anyLong(), any());
    verify(repository, never()).delete(any(), any(), anyLong());
  }

  @Test
  void rejectsLostUpdateAndInvalidText() {
    UUID id = UUID.randomUUID(), author = UUID.randomUUID();
    when(repository.find(id, author)).thenReturn(Optional.of(story(id, author)));
    assertEquals(
        StoryException.Reason.VERSION_CHANGED,
        assertThrows(
                StoryException.class,
                () -> service.update(id, author, 0, new StoryContent("Valid", "Text")))
            .reason());
    assertEquals(
        StoryException.Reason.INVALID_INPUT,
        assertThrows(
                StoryException.class, () -> service.create(author, new StoryContent("Valid", " ")))
            .reason());
  }

  @Test
  void cursorPreservesTimestampAndTieBreakerAndRejectsMalformedInput() {
    var position =
        new PagePosition(Instant.parse("2026-10-31T12:30:45.123456Z"), UUID.randomUUID());
    assertEquals(
        position, FeedCursor.decode(FeedCursor.encode(position.createdAt(), position.id())));
    for (String bad : List.of("", "not-a-cursor", "x".repeat(181)))
      assertThrows(StoryException.class, () -> FeedCursor.decode(bad));
    assertThrows(
        StoryException.class, () -> service.feed(UUID.randomUUID(), false, false, null, 21));
  }

  private Story story(UUID id, UUID author) {
    return new Story(
        id,
        author,
        "Name",
        null,
        "Title",
        "Body",
        0,
        Instant.now(),
        Instant.now(),
        0,
        0,
        0,
        0,
        "PUBLISHED",
        null,
        0L,
        List.of());
  }
}
