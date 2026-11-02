package io.plottwist.domain.chat.model;

import java.time.Instant;
import java.util.UUID;

public record ChatSummary(
    UUID id,
    ChatPeer peer,
    Instant updatedAt,
    long lastSequence,
    long readThrough,
    long peerReadThrough,
    long unreadCount,
    MessagePreview lastMessage) {}
