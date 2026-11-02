package io.plottwist.domain.chat.application;

import io.plottwist.domain.chat.application.port.ChatRepository;
import io.plottwist.domain.chat.model.*;
import io.plottwist.domain.profile.application.ProfileService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ChatService {
  private final ChatRepository chats;
  private final ProfileService profiles;

  public ChatService(ChatRepository chats, ProfileService profiles) {
    this.chats = chats;
    this.profiles = profiles;
  }

  @Transactional
  public ChatSummary open(UUID actor, UUID recipient) {
    if (recipient == null || actor.equals(recipient)) throw invalid("Choose another user");
    boolean ordered = actor.toString().compareTo(recipient.toString()) < 0;
    UUID low = ordered ? actor : recipient, high = ordered ? recipient : actor;
    chats.lockPair(low, high);
    var found = chats.pair(low, high);
    UUID id;
    if (found.isPresent()) id = found.get();
    else {
      profiles.publicProfile(recipient);
      id = UUID.randomUUID();
      chats.create(id, low, high, actor);
    }
    chats.show(id, actor);
    return get(id, actor);
  }

  public ChatSummary get(UUID id, UUID actor) {
    return chats.summary(id, actor).orElseThrow(ChatService::missing);
  }

  public ChatPage inbox(UUID actor, String cursor, int limit) {
    checkLimit(limit, 30);
    var rows = chats.inbox(actor, ChatCursor.decode(cursor, actor), limit + 1);
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    var last = items.isEmpty() ? null : items.getLast();
    return new ChatPage(
        items,
        rows.size() > limit
            ? ChatCursor.encode(actor, new ChatCursor.ChatSummaryKey(last.updatedAt(), last.id()))
            : null);
  }

  @Transactional
  public SendResult send(UUID id, UUID actor, UUID clientId, String body) {
    if (clientId == null
        || body == null
        || body.length() > 4000
        || body.indexOf(0) >= 0
        || body.isBlank()) throw invalid("Message must contain 1–4000 characters without NUL");
    body = body.replace("\r\n", "\n").replace('\r', '\n').strip();
    var chat = chats.lock(id, actor).orElseThrow(ChatService::missing);
    var old = chats.existing(id, actor, clientId);
    if (old.isPresent()) {
      if (!old.get().body().equals(body))
        throw new ChatException(
            ChatException.Reason.CONFLICT, "This message ID was already used for different text");
      return new SendResult(old.get(), false);
    }
    var message = chats.insert(id, chat.lastSequence() + 1, actor, clientId, body);
    chats.advance(id, message.sequence(), message.createdAt());
    return new SendResult(message, true);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public MessagePage messages(UUID id, UUID actor, Long before, Long after, int limit) {
    checkLimit(limit, 100);
    if (before != null && after != null
        || before != null && before < 1
        || after != null && after < 0) throw invalid("Use one valid before/after sequence");
    var chat = get(id, actor);
    if (after != null && after > chat.lastSequence()) throw invalid("Invalid after sequence");
    var rows = chats.messages(id, before, after, limit + 1);
    var page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
    boolean extra = rows.size() > limit;
    if (after == null) Collections.reverse(page);
    return new MessagePage(
        List.copyOf(page),
        after == null && extra ? page.getFirst().sequence() : null,
        after != null && extra,
        chat.lastSequence(),
        chat.peerReadThrough());
  }

  @Transactional
  public ChatSummary read(UUID id, UUID actor, long through) {
    var chat = chats.lock(id, actor).orElseThrow(ChatService::missing);
    if (through < 0 || through > chat.lastSequence())
      throw invalid("Read position is outside this chat");
    chats.read(id, actor, through);
    return get(id, actor);
  }

  private static void checkLimit(int limit, int max) {
    if (limit < 1 || limit > max) throw invalid("Invalid page size");
  }

  private static ChatException missing() {
    return new ChatException(ChatException.Reason.NOT_FOUND, "Chat not found");
  }

  private static ChatException invalid(String detail) {
    return new ChatException(ChatException.Reason.INVALID, detail);
  }
}
