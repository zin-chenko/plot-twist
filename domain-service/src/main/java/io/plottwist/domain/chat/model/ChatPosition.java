package io.plottwist.domain.chat.model;

import java.time.Instant;
import java.util.UUID;

public record ChatPosition(Instant updatedAt, UUID id) {}
