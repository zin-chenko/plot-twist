package io.plottwist.domain.recommendation.model;

import java.util.UUID;

public record RecommendationBatch(UUID id, String modelKey, String mode) {}
