package io.plottwist.domain.story.api;

import io.plottwist.domain.config.AccountIdentity;
import io.plottwist.domain.profile.application.ProfileService;
import io.plottwist.domain.story.application.StoryService;
import io.plottwist.domain.story.model.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{account}/stories")
public class AuthorStoriesController {
  private final StoryService stories;
  private final ProfileService profiles;

  public AuthorStoriesController(StoryService stories, ProfileService profiles) {
    this.stories = stories;
    this.profiles = profiles;
  }

  @GetMapping
  ResponseEntity<FeedPage<Story>> feed(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID account,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit) {
    var viewer = AccountIdentity.require(jwt);
    profiles.publicProfile(account);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(stories.publishedByAuthor(viewer, account, cursor, limit));
  }
}
