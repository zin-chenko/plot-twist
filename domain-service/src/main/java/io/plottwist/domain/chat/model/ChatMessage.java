package io.plottwist.domain.chat.model;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(
    long sequence, UUID senderId, UUID clientMessageId, String body, Instant createdAt) {}
