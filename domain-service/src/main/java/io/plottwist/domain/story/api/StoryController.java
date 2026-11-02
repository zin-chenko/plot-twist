package io.plottwist.domain.story.api;

import io.plottwist.domain.config.AccountIdentity;
import io.plottwist.domain.story.api.request.*;
import io.plottwist.domain.story.application.StoryAuthorPhotos;
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
  private final StoryAuthorPhotos photos;

  public StoryController(StoryService stories, StoryAuthorPhotos photos) {
    this.stories = stories;
    this.photos = photos;
  }

  @GetMapping
  ResponseEntity<FeedPage<Story>> feed(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "false") boolean liked,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(stories.feed(AccountIdentity.require(jwt), mine, liked, cursor, limit));
  }

  @GetMapping("/{id}")
  ResponseEntity<Story> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return response(stories.get(id, AccountIdentity.require(jwt)));
  }

  @GetMapping("/{id}/author/photo")
  ResponseEntity<byte[]> authorPhoto(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    stories.get(id, AccountIdentity.require(jwt));
    var photo = photos.metadata(id);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=author.jpg")
        .header("X-Content-Type-Options", "nosniff")
        .eTag(photo.sha256())
        .body(photos.read(photo));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Story> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StoryRequest body) {
    var story = stories.create(AccountIdentity.require(jwt), body.content());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/stories/" + story.id()))
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
    var story = stories.update(id, AccountIdentity.require(jwt), version(match), body.content());
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .eTag(tag(story))
        .body(story);
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
        .eTag(Long.toString(comment.version()))
        .body(comment);
  }

  @GetMapping("/{id}/comments/{commentId}")
  ResponseEntity<StoryComment> getComment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID commentId) {
    stories.get(id, AccountIdentity.require(jwt));
    var comment = stories.getComment(id, commentId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(Long.toString(comment.version()))
        .body(comment);
  }

  @PutMapping(value = "/{id}/comments/{commentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<StoryComment> editComment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID commentId,
      @RequestHeader(value = "If-Match", required = false) String match,
      @Valid @RequestBody CommentRequest body) {
    var comment =
        stories.updateComment(
            id, commentId, AccountIdentity.require(jwt), version(match), body.body());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(Long.toString(comment.version()))
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
