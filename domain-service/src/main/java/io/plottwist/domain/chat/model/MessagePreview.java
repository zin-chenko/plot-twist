package io.plottwist.domain.chat.model;

import java.time.Instant;
import java.util.UUID;

public record MessagePreview(long sequence, UUID senderId, String body, Instant createdAt) {}
