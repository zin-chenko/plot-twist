package io.plottwist.domain.chat.application.port;

import io.plottwist.domain.chat.model.*;
import java.time.Instant;
import java.util.*;

public interface ChatRepository {
  void lockPair(UUID low, UUID high);

  Optional<UUID> pair(UUID low, UUID high);

  void create(UUID id, UUID low, UUID high, UUID actor);

  void show(UUID id, UUID actor);

  Optional<ChatSummary> summary(UUID id, UUID actor);

  List<ChatSummary> inbox(UUID actor, ChatPosition before, int limit);

  Optional<LockedChat> lock(UUID id, UUID actor);

  Optional<ChatMessage> existing(UUID chat, UUID actor, UUID clientId);

  ChatMessage insert(UUID chat, long sequence, UUID actor, UUID clientId, String body);

  void advance(UUID chat, long sequence, Instant createdAt);

  List<ChatMessage> messages(UUID chat, Long before, Long after, int limit);

  void read(UUID chat, UUID actor, long through);
}
