package io.plottwist.domain.recommendation.api;

import io.plottwist.domain.config.AccountIdentity;
import io.plottwist.domain.recommendation.application.RecommendationService;
import io.plottwist.domain.recommendation.model.RecommendationPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationController {
  private final RecommendationService recommendations;

  public RecommendationController(RecommendationService recommendations) {
    this.recommendations = recommendations;
  }

  @GetMapping("/api/v1/recommendations")
  ResponseEntity<RecommendationPage> feed(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "false") boolean refresh) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(recommendations.feed(AccountIdentity.require(jwt), cursor, limit, refresh));
  }

  @PostMapping(value = "/api/v1/stories/{id}/reading", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> reading(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Reading body) {
    recommendations.reading(AccountIdentity.require(jwt), id, body.completed(), body.seconds());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  public record Reading(@NotNull Boolean completed, @NotNull @Min(0) @Max(600) Integer seconds) {}
}
