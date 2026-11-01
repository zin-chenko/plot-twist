package io.plottwist.domain.story.model;

import java.time.Instant;
import java.util.UUID;

public record PagePosition(Instant createdAt, UUID id) {}
