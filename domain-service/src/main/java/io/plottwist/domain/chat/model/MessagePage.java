package io.plottwist.domain.chat.model;

import java.util.List;

public record MessagePage(
    List<ChatMessage> items,
    Long nextBefore,
    boolean hasMoreNewer,
    long lastSequence,
    long peerReadThrough) {}
