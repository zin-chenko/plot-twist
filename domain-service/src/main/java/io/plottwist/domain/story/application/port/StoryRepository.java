package io.plottwist.domain.story.application.port;

import io.plottwist.domain.story.model.*;
import java.util.*;

public interface StoryRepository {
  List<Story> feed(UUID viewer, UUID author, boolean liked, PagePosition before, int limit);

  List<Story> publishedByAuthor(UUID viewer, UUID author, PagePosition before, int limit);

  List<Story> following(UUID viewer, PagePosition before, int limit);

  List<Story> findMany(List<UUID> ids, UUID viewer);

  Optional<Story> find(UUID id, UUID viewer);

  void create(UUID id, UUID author, StoryContent content);

  boolean update(UUID id, UUID author, long version, StoryContent content);

  boolean delete(UUID id, UUID author, long version);

  boolean lockForInteraction(UUID id);

  void react(UUID story, UUID account, int value);

  Optional<AuthorPhoto> authorPhoto(UUID story);
}
