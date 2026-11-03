package io.plottwist.domain.story.api;

import io.plottwist.domain.config.AccountIdentity;
import io.plottwist.domain.story.api.request.CommentRequest;
import io.plottwist.domain.story.application.CommentService;
import io.plottwist.domain.story.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stories/{id}/comments")
public class CommentController {
  private final CommentService comments;

  public CommentController(CommentService comments) {
    this.comments = comments;
  }

  public record NewComment(@NotBlank @Size(max = 2000) String body, UUID parentId) {}

  @GetMapping
  ResponseEntity<FeedPage<StoryComment>> page(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "newest") String sort) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(comments.page(id, AccountIdentity.require(jwt), null, sort, cursor, limit));
  }

  @GetMapping("/{commentId}/replies")
  ResponseEntity<FeedPage<StoryComment>> replies(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID commentId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(comments.page(id, AccountIdentity.require(jwt), commentId, "oldest", cursor, limit));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<StoryComment> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody NewComment body) {
    var result = comments.create(id, AccountIdentity.require(jwt), body.body(), body.parentId());
    return ResponseEntity.created(URI.create("/api/v1/stories/" + id + "/comments/" + result.id()))
        .cacheControl(CacheControl.noStore())
        .eTag("\"" + result.version() + "\"")
        .body(result);
  }

  @GetMapping("/{commentId}")
  ResponseEntity<StoryComment> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID commentId) {
    return ok(comments.get(id, commentId, AccountIdentity.require(jwt)));
  }

  @PutMapping(value = "/{commentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<StoryComment> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID commentId,
      @RequestHeader(value = "If-Match", required = false) String match,
      @Valid @RequestBody CommentRequest body) {
    return ok(
        comments.update(
            id,
            commentId,
            AccountIdentity.require(jwt),
            StoryController.version(match),
            body.body()));
  }

  @PutMapping("/{commentId}/like")
  ResponseEntity<StoryComment> like(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID commentId) {
    return ok(comments.like(id, commentId, AccountIdentity.require(jwt), true));
  }

  @DeleteMapping("/{commentId}/like")
  ResponseEntity<StoryComment> unlike(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID commentId) {
    return ok(comments.like(id, commentId, AccountIdentity.require(jwt), false));
  }

  @DeleteMapping("/{commentId}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID commentId) {
    comments.delete(id, commentId, AccountIdentity.require(jwt));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private ResponseEntity<StoryComment> ok(StoryComment result) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag("\"" + result.version() + "\"")
        .body(result);
  }
}
