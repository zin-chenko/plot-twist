package io.plottwist.domain.recommendation.model;

import java.util.UUID;

public record IndexJob(UUID storyId, long generation, UUID token, int attempts) {}
