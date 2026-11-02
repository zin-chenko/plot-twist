package io.plottwist.api.attachment.api;

import io.plottwist.api.attachment.application.port.MediaBackend;
import io.plottwist.api.shared.application.port.DomainBackend;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/story-media")
public class AttachmentGateway {
  private final DomainBackend domain;
  private final MediaBackend media;

  public AttachmentGateway(DomainBackend domain, MediaBackend media) {
    this.domain = domain;
    this.media = media;
  }

  @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  void upload(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    requireUser(jwt);
    media.transfer(
        "POST",
        "/api/v1/story-media",
        jwt.getTokenValue(),
        Map.of(),
        request.getInputStream(),
        request.getContentLengthLong(),
        m -> {
          response.setStatus(m.status());
          m.headers().forEach((k, v) -> response.setHeader(k, String.join(",", v)));
        },
        response.getOutputStream());
  }

  @GetMapping("/{id}/access")
  ResponseEntity<byte[]> access(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    requireUser(jwt);
    return domain.exchange(
        HttpMethod.GET,
        "/api/v1/story-media/" + id + "/access",
        jwt.getTokenValue(),
        Map.of(),
        null,
        null);
  }

  @DeleteMapping("/{id}")
  ResponseEntity<byte[]> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    requireUser(jwt);
    return domain.exchange(
        HttpMethod.DELETE, "/api/v1/story-media/" + id, jwt.getTokenValue(), Map.of(), null, null);
  }

  @RequestMapping(
      value = "/{id}/content",
      method = {RequestMethod.GET, RequestMethod.HEAD})
  void content(
      @PathVariable UUID id,
      @RequestParam(required = false) String ticket,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (ticket != null && ticket.length() > 300)
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.NOT_FOUND, "Invalid media link");
    var range = request.getHeader("Range");
    var headers = range == null ? Map.<String, String>of() : Map.of("Range", range);
    String path =
        "/api/v1/story-media/"
            + id
            + "/content"
            + (ticket == null
                ? ""
                : "?ticket="
                    + java.net.URLEncoder.encode(ticket, java.nio.charset.StandardCharsets.UTF_8));
    media.transfer(
        request.getMethod(),
        path,
        null,
        headers,
        null,
        0,
        m -> {
          response.setStatus(m.status());
          m.headers().forEach((k, v) -> response.setHeader(k, String.join(",", v)));
        },
        response.getOutputStream());
  }

  private static void requireUser(Jwt jwt) {
    try {
      var id = jwt.getClaimAsString("account_id");
      if (id == null || !id.equals(jwt.getSubject())) throw new IllegalArgumentException();
      UUID.fromString(id);
    } catch (IllegalArgumentException invalid) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.FORBIDDEN, "A verified user account is required");
    }
  }
}
