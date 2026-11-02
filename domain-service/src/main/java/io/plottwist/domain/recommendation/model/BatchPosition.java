package io.plottwist.domain.recommendation.model;

import java.util.UUID;

public record BatchPosition(UUID storyId, int position) {}
