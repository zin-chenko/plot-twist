package io.plottwist.api.profile.api;

import io.plottwist.api.shared.application.port.DomainBackend;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/profiles/{account}")
public class PublicProfileGateway {
  private final DomainBackend domain;

  public PublicProfileGateway(DomainBackend domain) {
    this.domain = domain;
  }

  @GetMapping({"", "/photo"})
  ResponseEntity<byte[]> get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID account,
      jakarta.servlet.http.HttpServletRequest request) {
    requireUser(jwt);
    return domain.exchange(
        HttpMethod.GET,
        "/api/v1/profiles/"
            + account
            + (request.getRequestURI().endsWith("/photo") ? "/photo" : ""),
        jwt.getTokenValue(),
        Map.of(),
        null,
        null);
  }

  @GetMapping("/stories")
  ResponseEntity<byte[]> stories(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID account,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "10") int limit) {
    requireUser(jwt);
    var uri =
        UriComponentsBuilder.fromPath("/api/v1/profiles/" + account + "/stories")
            .queryParam("limit", limit);
    if (cursor != null) uri.queryParam("cursor", cursor);
    return domain.exchange(
        HttpMethod.GET,
        uri.build().encode().toUriString(),
        jwt.getTokenValue(),
        Map.of(),
        null,
        null);
  }

  private static void requireUser(Jwt jwt) {
    try {
      String id = jwt.getClaimAsString("account_id");
      if (id == null || !id.equals(jwt.getSubject())) throw new IllegalArgumentException();
      UUID.fromString(id);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "A verified user account is required");
    }
  }
}
