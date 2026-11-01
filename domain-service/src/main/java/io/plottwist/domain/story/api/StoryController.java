package io.plottwist.domain.story.api;

import io.plottwist.domain.story.api.request.*;
import io.plottwist.domain.story.application.StoryService;
import io.plottwist.domain.story.model.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/stories")
public class StoryController {
  private final StoryService stories;

  public StoryController(StoryService stories) {
    this.stories = stories;
  }

  @GetMapping
  ResponseEntity<FeedPage<Story>> feed(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(stories.feed(AccountIdentity.require(jwt), mine, cursor, limit));
  }

  @GetMapping("/{id}")
  ResponseEntity<Story> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return response(stories.get(id, AccountIdentity.require(jwt)));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Story> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StoryRequest body) {
    var story = stories.create(AccountIdentity.require(jwt), body.content());
    return ResponseEntity.created(URI.create("/api/v1/stories/" + story.id()))
        .cacheControl(CacheControl.noStore())
        .eTag(tag(story))
        .body(story);
  }

  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Story> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String match,
      @Valid @RequestBody StoryRequest body) {
    return response(
        stories.update(id, AccountIdentity.require(jwt), version(match), body.content()));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String match) {
    stories.delete(id, AccountIdentity.require(jwt), version(match));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @PutMapping(value = "/{id}/reaction", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Story> react(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ReactionRequest body) {
    return response(stories.react(id, AccountIdentity.require(jwt), body.value()));
  }

  @GetMapping("/{id}/comments")
  ResponseEntity<FeedPage<StoryComment>> comments(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(stories.comments(id, AccountIdentity.require(jwt), cursor, limit));
  }

  @PostMapping(value = "/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<StoryComment> comment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody CommentRequest body) {
    var comment = stories.comment(id, AccountIdentity.require(jwt), body.body());
    return ResponseEntity.created(URI.create("/api/v1/stories/" + id + "/comments/" + comment.id()))
        .cacheControl(CacheControl.noStore())
        .body(comment);
  }

  @DeleteMapping("/{id}/comments/{commentId}")
  ResponseEntity<Void> deleteComment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID commentId) {
    stories.deleteComment(id, commentId, AccountIdentity.require(jwt));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private static ResponseEntity<Story> response(Story story) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(tag(story)).body(story);
  }

  private static String tag(Story story) {
    return "\"" + story.version() + "\"";
  }

  static long version(String match) {
    if (match == null)
      throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
    if (!match.matches("\"[0-9]{1,18}\""))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a quoted numeric If-Match");
    return Long.parseLong(match.substring(1, match.length() - 1));
  }
}
