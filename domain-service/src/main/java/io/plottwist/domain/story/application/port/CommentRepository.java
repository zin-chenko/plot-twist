package io.plottwist.domain.story.application.port;

import io.plottwist.domain.story.model.*;
import java.util.*;

public interface CommentRepository {
  List<StoryComment> page(
      UUID story, UUID viewer, UUID root, CommentSort sort, PagePosition position, int limit);

  Optional<StoryComment> find(UUID story, UUID comment, UUID viewer);

  boolean lock(UUID story, UUID comment);

  void create(UUID id, UUID story, UUID author, String body, UUID parent, UUID root);

  boolean update(UUID story, UUID comment, UUID author, long version, String body);

  void delete(UUID story, UUID comment);

  void like(UUID comment, UUID account, boolean liked);

  UUID createRanking(UUID story, UUID viewer);

  boolean rankingExists(UUID batch, UUID story, UUID viewer);

  List<RankedComment> rankedPage(UUID batch, UUID viewer, int after, int limit);
}
