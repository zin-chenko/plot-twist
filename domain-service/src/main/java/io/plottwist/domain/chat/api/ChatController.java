package io.plottwist.domain.chat.api;

import io.plottwist.domain.chat.application.ChatService;
import io.plottwist.domain.chat.model.*;
import io.plottwist.domain.config.AccountIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chats")
public class ChatController {
  private final ChatService chats;

  public ChatController(ChatService chats) {
    this.chats = chats;
  }

  public record OpenChat(@NotNull UUID recipientId) {}

  public record NewMessage(
      @NotNull UUID clientMessageId, @NotBlank @Size(max = 4000) String body) {}

  public record ReadChat(@NotNull @Min(0) Long throughSequence) {}

  @PostMapping
  ResponseEntity<ChatSummary> open(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenChat body) {
    return ok(chats.open(AccountIdentity.require(jwt), body.recipientId()));
  }

  @GetMapping
  ResponseEntity<ChatPage> inbox(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return ok(chats.inbox(AccountIdentity.require(jwt), cursor, limit));
  }

  @GetMapping("/{id}")
  ResponseEntity<ChatSummary> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return ok(chats.get(id, AccountIdentity.require(jwt)));
  }

  @GetMapping("/{id}/messages")
  ResponseEntity<MessagePage> messages(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(required = false) Long before,
      @RequestParam(required = false) Long after,
      @RequestParam(defaultValue = "50") int limit) {
    return ok(chats.messages(id, AccountIdentity.require(jwt), before, after, limit));
  }

  @PostMapping("/{id}/messages")
  ResponseEntity<ChatMessage> send(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody NewMessage body) {
    var result = chats.send(id, AccountIdentity.require(jwt), body.clientMessageId(), body.body());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .cacheControl(CacheControl.noStore())
        .body(result.message());
  }

  @PutMapping("/{id}/read")
  ResponseEntity<ChatSummary> read(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReadChat body) {
    return ok(chats.read(id, AccountIdentity.require(jwt), body.throughSequence()));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
