package io.plottwist.domain.recommendation.application.port;

import io.plottwist.domain.recommendation.model.*;
import java.util.*;

public interface VectorIndex {
  boolean ensure(EmbeddingModel model);

  void upsert(EmbeddingModel model, IndexDocument document, UUID point, float[] vector);

  void deleteEveryModelStory(UUID story);

  void deleteStory(EmbeddingModel model, UUID story);

  List<VectorHit> recommend(EmbeddingModel model, UUID viewer, Seeds seeds, int limit);

  VectorPage scan(EmbeddingModel model, UUID offset, int limit);

  void deletePoints(EmbeddingModel model, List<UUID> points);

  record VectorPage(List<VectorHit> items, UUID next) {}
}
