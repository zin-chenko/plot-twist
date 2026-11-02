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
    assertEquals(
        StoryException.Reason.INVALID_INPUT,
        assertThrows(StoryException.class, () -> service.comment(id, author, "Bad\u0000text"))
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

  @Test
  void rejectsForeignCommentDeletion() {
    UUID story = UUID.randomUUID(), comment = UUID.randomUUID(), author = UUID.randomUUID();
    when(repository.commentAuthor(story, comment)).thenReturn(Optional.of(UUID.randomUUID()));
    assertEquals(
        StoryException.Reason.FORBIDDEN,
        assertThrows(StoryException.class, () -> service.deleteComment(story, comment, author))
            .reason());
    verify(repository, never()).deleteComment(any(), any(), any());
  }

  @Test
  void refusesForeignCommentEditsAndStaleVersions() {
    UUID story = UUID.randomUUID(),
        comment = UUID.randomUUID(),
        author = UUID.randomUUID(),
        other = UUID.randomUUID();
    when(repository.findComment(story, comment))
        .thenReturn(
            Optional.of(
                new StoryComment(
                    comment, story, author, "Author", "Body", Instant.now(), Instant.now(), 0)));
    assertEquals(
        StoryException.Reason.FORBIDDEN,
        assertThrows(
                StoryException.class,
                () -> service.updateComment(story, comment, other, 0, "Stolen"))
            .reason());
    verify(repository, never()).updateComment(any(), any(), eq(other), anyLong(), anyString());
    assertEquals(
        StoryException.Reason.VERSION_CHANGED,
        assertThrows(
                StoryException.class,
                () -> service.updateComment(story, comment, author, 0, "New text"))
            .reason());
    assertEquals(
        StoryException.Reason.INVALID_INPUT,
        assertThrows(
                StoryException.class, () -> service.updateComment(story, comment, author, 0, " "))
            .reason());
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
