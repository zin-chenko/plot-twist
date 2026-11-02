package io.plottwist.domain.recommendation.application;

public class RecommendationUnavailable extends RuntimeException {
  public RecommendationUnavailable(String message) {
    super(message);
  }
}
