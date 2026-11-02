package io.plottwist.domain.recommendation.application.port;

import io.plottwist.domain.recommendation.model.*;

public interface EmbeddingGateway {
  EmbeddingModel model();

  float[] embed(IndexDocument document, EmbeddingModel model);
}
