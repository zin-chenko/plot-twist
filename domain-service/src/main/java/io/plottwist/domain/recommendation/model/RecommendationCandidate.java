package io.plottwist.domain.recommendation.model;

import java.time.Instant;
import java.util.UUID;

public record RecommendationCandidate(
    UUID id, UUID author, long version, Instant createdAt, long likes, long dislikes) {}
