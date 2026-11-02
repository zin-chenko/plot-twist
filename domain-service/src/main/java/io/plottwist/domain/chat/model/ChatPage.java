package io.plottwist.domain.chat.model;

import java.util.List;

public record ChatPage(List<ChatSummary> items, String nextCursor) {}
