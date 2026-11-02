package io.plottwist.domain.profile.api;

import io.plottwist.domain.config.AccountIdentity;
import io.plottwist.domain.profile.application.ProfileService;
import io.plottwist.domain.profile.model.PublicProfile;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{account}")
public class PublicProfileController {
  private final ProfileService profiles;

  public PublicProfileController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  ResponseEntity<PublicProfile> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID account) {
    AccountIdentity.require(jwt);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(profiles.publicProfile(account));
  }

  @GetMapping("/photo")
  ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID account) {
    AccountIdentity.require(jwt);
    var profile = profiles.publicProfile(account);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(CacheControl.noStore())
        .eTag(profile.photoSha256())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=avatar.jpg")
        .header("X-Content-Type-Options", "nosniff")
        .body(profiles.publicPhoto(profile));
  }
}
