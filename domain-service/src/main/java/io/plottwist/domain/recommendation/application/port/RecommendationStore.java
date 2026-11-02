package io.plottwist.domain.recommendation.application.port;

import io.plottwist.domain.recommendation.model.*;
import java.util.*;

public interface RecommendationStore {
  long revision(UUID viewer);

  Optional<RecommendationBatch> recent(UUID viewer, long revision, String model);

  Seeds seeds(UUID viewer, EmbeddingModel model);

  List<RecommendationCandidate> candidates(UUID viewer, Collection<UUID> ids, int limit);

  RecommendationBatch save(
      UUID viewer, long revision, String model, String mode, List<RecommendationCandidate> ranked);

  Optional<RecommendationBatch> batch(UUID viewer, UUID id);

  List<BatchPosition> page(UUID viewer, UUID batch, int after, int limit);

  boolean recordReading(UUID viewer, UUID story, boolean read);

  void cleanup();
}
