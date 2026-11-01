package io.plottwist.domain.story.application.port;

import io.plottwist.domain.story.model.*;
import java.util.*;

public interface StoryRepository {
  List<Story> feed(UUID viewer, UUID author, PagePosition before, int limit);

  Optional<Story> find(UUID id, UUID viewer);

  void create(UUID id, UUID author, StoryContent content);

  boolean update(UUID id, UUID author, long version, StoryContent content);

  boolean delete(UUID id, UUID author, long version);

  boolean lockForInteraction(UUID id);

  void react(UUID story, UUID account, int value);

  List<StoryComment> comments(UUID story, PagePosition before, int limit);

  StoryComment comment(UUID id, UUID story, UUID author, String body);

  Optional<UUID> commentAuthor(UUID story, UUID comment);

  boolean deleteComment(UUID story, UUID comment, UUID author);
}
