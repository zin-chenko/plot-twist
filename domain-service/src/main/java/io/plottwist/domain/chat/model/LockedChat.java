package io.plottwist.domain.chat.model;

import java.util.UUID;

public record LockedChat(UUID id, long lastSequence) {}
