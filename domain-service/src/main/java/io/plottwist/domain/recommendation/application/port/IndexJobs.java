package io.plottwist.domain.recommendation.application.port;

import io.plottwist.domain.recommendation.model.*;
import java.util.*;

public interface IndexJobs {
  Optional<IndexJob> claim();

  Optional<IndexJob> claimDeleted();

  Optional<IndexDocument> document(UUID id);

  boolean owned(IndexJob job);

  void complete(IndexJob job, IndexDocument document, EmbeddingModel model, UUID point);

  void release(IndexJob job);

  void failed(IndexJob job, String reason);

  void repair(EmbeddingModel model, boolean lostCollection);

  Set<UUID> obsolete(List<VectorHit> hits);

  long pending();

  long dead();
}
