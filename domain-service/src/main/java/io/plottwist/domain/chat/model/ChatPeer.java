package io.plottwist.domain.chat.model;

import java.util.UUID;

public record ChatPeer(UUID accountId, String displayName, String photoSha256) {}
