package io.plottwist.domain.recommendation.model;

public record EmbeddingModel(String key, int dimensions) {
  public EmbeddingModel {
    if (key == null || !key.matches("[0-9a-f]{64}") || dimensions != 384)
      throw new IllegalArgumentException("Unexpected embedding model identity");
  }

  public String collection() {
    return "stories_e5_" + key.substring(0, 24);
  }
}
