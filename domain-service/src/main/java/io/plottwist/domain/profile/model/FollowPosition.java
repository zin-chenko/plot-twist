package io.plottwist.domain.profile.model;

import java.time.Instant;
import java.util.UUID;

public record FollowPosition(Instant createdAt, UUID accountId) {}
