package io.plottwist.domain.recommendation.model;

import java.util.UUID;

public record VectorHit(UUID pointId, UUID storyId, long version, double score) {}
