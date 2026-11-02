package io.plottwist.domain.recommendation.application;

import io.plottwist.domain.recommendation.model.*;
import java.time.*;
import java.util.*;

public final class RecommendationRanker {
  private RecommendationRanker() {}

  public static List<RecommendationCandidate> rank(
      List<RecommendationCandidate> candidates, Map<UUID, Double> scores, Instant now) {
    var remaining = new ArrayList<>(candidates);
    var result = new ArrayList<RecommendationCandidate>();
    var authors = new HashMap<UUID, Integer>();
    // Greedy diversification; every fifth place explores a fresh, non-semantic candidate.
    while (!remaining.isEmpty() && result.size() < 150) {
      boolean explore = !scores.isEmpty() && result.size() % 5 == 4;
      RecommendationCandidate best = null;
      double maximum = -Double.MAX_VALUE;
      for (var c : remaining) {
        if (explore && scores.containsKey(c.id())) continue;
        double value = score(c, scores, now) - 0.16 * authors.getOrDefault(c.author(), 0);
        if (result.size() >= 2
            && result.getLast().author().equals(c.author())
            && result.get(result.size() - 2).author().equals(c.author())) value -= 1;
        if (value > maximum
            || value == maximum && best != null && c.id().compareTo(best.id()) < 0) {
          best = c;
          maximum = value;
        }
      }
      if (best == null) best = remaining.getFirst();
      result.add(best);
      remaining.remove(best);
      authors.merge(best.author(), 1, Integer::sum);
    }
    return List.copyOf(result);
  }

  private static double score(RecommendationCandidate c, Map<UUID, Double> semantic, Instant now) {
    double age = Math.max(0, Duration.between(c.createdAt(), now).toSeconds() / 3600.0);
    double freshness = Math.exp(-age / 168.0),
        quality = (c.likes() + 2.0) / (c.likes() + c.dislikes() + 4.0);
    if (semantic.isEmpty()) return .55 * quality + .45 * freshness;
    double similarity =
        semantic.containsKey(c.id())
            ? Math.max(0, Math.min(1, (semantic.get(c.id()) + 1) / 2))
            : .15;
    return .75 * similarity + .15 * quality + .10 * freshness;
  }
}
